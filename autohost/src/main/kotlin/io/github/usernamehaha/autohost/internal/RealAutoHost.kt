package io.github.usernamehaha.autohost.internal

import io.github.usernamehaha.autohost.AutoHost
import io.github.usernamehaha.autohost.AutoHostConfig
import io.github.usernamehaha.autohost.AutoHostEvent
import io.github.usernamehaha.autohost.AutoHostState
import io.github.usernamehaha.autohost.Host
import io.github.usernamehaha.autohost.HostStatus
import io.github.usernamehaha.autohost.ProbeResult
import io.github.usernamehaha.autohost.ProbeTrigger
import io.github.usernamehaha.autohost.SwitchReason
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class RealAutoHost(
    config: AutoHostConfig,
    private val store: SnapshotStore,
    private val networkMonitor: NetworkMonitor,
    private val timeSource: TimeSource.WithComparableMarks,
    private val wallClock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onClose: () -> Unit = {},
) : AutoHost {
    private val strategy = config.strategy
    private val prober = config.prober
    private val probeTtl = config.probeTtl
    private val minProbeInterval = config.minProbeInterval
    private val probeOnNetworkChange = config.probeOnNetworkChange
    private val listener = config.listener

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean(false)
    private val _state: MutableStateFlow<AutoHostState>

    /** 最近一轮有效探测的完成时刻，用来判断测量结果是否过期。 */
    @Volatile
    private var lastRoundAt: ComparableTimeMark? = null

    /** 最近一次发起探测的时刻，用来给自动触发的探测节流。 */
    @Volatile
    private var lastProbeRequestedAt: ComparableTimeMark? = null

    private val probeLock = Any()
    private var probeJob: Deferred<List<HostStatus>>? = null

    /** 探测进行中线路列表或网络变了，这一轮结束后要再来一轮。 */
    private var rerunTrigger: ProbeTrigger? = null

    private val networkMonitorStarted: Boolean

    override val state: StateFlow<AutoHostState>

    init {
        val hosts = parseHosts(config.hosts)
        _state = MutableStateFlow(restore(hosts))
        state = _state.asStateFlow()
        networkMonitorStarted = try {
            networkMonitor.start(::onNetworkChanged)
            true
        } catch (e: Exception) {
            emit(AutoHostEvent.NetworkMonitorUnavailable(e))
            false
        }
        probeIfStale()
    }

    override val current: Host
        get() {
            probeIfStale()
            return _state.value.current
        }

    override fun rewrite(url: String): String {
        val parsed = ParsedUrl.parse(url) ?: return url
        probeIfStale()
        val snapshot = _state.value
        val matched = snapshot.hosts.firstOrNull { it.host.matches(parsed.httpUrl) }?.host ?: return url
        if (matched == snapshot.current) return url
        return parsed.withHost(matched, snapshot.current)
    }

    override fun reportSuccess(url: String) {
        if (closed.get()) return
        val host = findHost(url) ?: return
        // 绝大多数请求都走到这里：计数本来就是 0，不需要更新状态
        if (_state.value.hosts.none { it.host == host && it.consecutiveFailures > 0 }) return
        update { snapshot ->
            snapshot.copy(hosts = snapshot.hosts.map { if (it.host == host) it.copy(consecutiveFailures = 0) else it })
        }
    }

    override fun reportFailure(url: String, cause: Throwable?) {
        if (closed.get()) return
        val host = findHost(url) ?: return
        // 离线时每条线路都会失败，这些失败说明不了线路的好坏
        if (isOffline()) return
        val now = timeSource.markNow()
        val change = update(SwitchReason.FAILURE, reselect = true) { snapshot ->
            snapshot.copy(
                hosts = snapshot.hosts.map {
                    if (it.host != host) return@map it
                    it.copy(
                        consecutiveFailures = minOf(it.consecutiveFailures, Int.MAX_VALUE - 1) + 1,
                        lastFailureAt = now,
                    )
                },
            )
        }
        // 策略因为失败换了线路，说明手里的测量结果已经不反映现状
        if (change.switched) requestProbe(ProbeTrigger.FAILURE)
    }

    override fun updateHosts(hosts: List<String>) {
        val parsed = parseHosts(hosts)
        if (closed.get()) return
        // 接入方通常每次拉到远程配置都会调一遍，列表没变就不要白白探测一轮
        if (_state.value.hosts.map { it.host } == parsed) return
        val change = update(SwitchReason.HOSTS_UPDATED, reselect = true) { snapshot ->
            val known = snapshot.hosts.associateBy { it.host }
            snapshot.copy(
                hosts = parsed.map { known[it] ?: HostStatus(it) },
                pinned = snapshot.pinned?.takeIf { it in parsed },
            )
        }
        emit(AutoHostEvent.HostsUpdated(parsed))
        val clearedPin = change.old.pinned?.takeIf { change.new.pinned == null }
        if (clearedPin != null) emit(AutoHostEvent.PinCleared(clearedPin))
        requestProbe(ProbeTrigger.HOSTS_UPDATED)
    }

    override fun pin(host: Host) {
        if (closed.get()) return
        update(SwitchReason.PINNED) { snapshot ->
            require(snapshot.hosts.any { it.host == host }) { "$host 不在线路列表里" }
            snapshot.copy(current = host, pinned = host)
        }
    }

    override fun unpin() {
        if (closed.get()) return
        update(SwitchReason.UNPINNED, reselect = true) { it.copy(pinned = null) }
        probeIfStale()
    }

    override fun refresh() {
        requestProbe(ProbeTrigger.MANUAL)
    }

    override suspend fun probe(): List<HostStatus> {
        val job = requestProbe(ProbeTrigger.MANUAL) ?: return _state.value.hosts
        return try {
            job.await()
        } catch (_: CancellationException) {
            // 被取消的如果是调用方自己，继续向上抛；如果是实例被 close，返回现有的事实
            currentCoroutineContext().ensureActive()
            _state.value.hosts
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        if (networkMonitorStarted) networkMonitor.stop()
        update { it.copy(isProbing = false) }
        onClose()
    }

    // region 探测

    private fun probeIfStale() {
        if (closed.get()) return
        val last = lastRoundAt
        if (last != null && last.elapsedNow() < probeTtl) return
        requestProbe(ProbeTrigger.STALE)
    }

    private fun onNetworkChanged() {
        if (!probeOnNetworkChange) return
        // 换了网络，之前的测量结果全部作废；即使这次因为 pin 没有探测，取消固定后也会补上
        lastRoundAt = null
        requestProbe(ProbeTrigger.NETWORK_CHANGED)
    }

    private fun requestProbe(trigger: ProbeTrigger): Deferred<List<HostStatus>>? {
        if (closed.get()) return null
        val manual = trigger == ProbeTrigger.MANUAL
        if (!manual && _state.value.pinned != null) return null
        if (trigger == ProbeTrigger.STALE || trigger == ProbeTrigger.FAILURE) {
            val last = lastProbeRequestedAt
            if (last != null && last.elapsedNow() < minProbeInterval) return null
        }
        lastProbeRequestedAt = timeSource.markNow()
        if (isOffline()) {
            emit(AutoHostEvent.ProbeSkippedOffline(trigger))
            return null
        }
        synchronized(probeLock) {
            probeJob?.let { running ->
                if (trigger == ProbeTrigger.HOSTS_UPDATED || trigger == ProbeTrigger.NETWORK_CHANGED) {
                    rerunTrigger = trigger
                }
                return running
            }
            return scope.async { runRounds(trigger) }.also { probeJob = it }
        }
    }

    private suspend fun runRounds(firstTrigger: ProbeTrigger): List<HostStatus> {
        var trigger = firstTrigger
        var finished = false
        try {
            while (true) {
                emit(AutoHostEvent.ProbeStarted(trigger))
                update { it.copy(isProbing = true) }
                val targets = _state.value.hosts.map { it.host }
                val results = coroutineScope {
                    targets.map { host -> async { host to probeSafely(host) } }.awaitAll()
                }
                if (isOffline()) {
                    // 探测途中断网了，这一轮的失败不是线路的问题
                    emit(AutoHostEvent.ProbeSkippedOffline(trigger))
                } else {
                    applyResults(results.toMap())
                }
                // 判断"要不要再来一轮"和"这个 job 结束了"必须在同一把锁里完成，
                // 否则会有请求在两者之间挂到一个即将结束的 job 上，它要求的重跑就丢了
                trigger = synchronized(probeLock) {
                    val next = rerunTrigger
                    rerunTrigger = null
                    if (next == null) probeJob = null
                    next
                } ?: break
            }
            finished = true
        } finally {
            // 被取消或意外出错时也要让出位置，否则之后永远不会再探测
            if (!finished) synchronized(probeLock) { probeJob = null }
            update { it.copy(isProbing = false) }
        }
        return _state.value.hosts
    }

    private suspend fun probeSafely(host: Host): ProbeResult =
        try {
            withTimeoutOrNull(PROBE_HARD_TIMEOUT) { prober.probe(host) }
                ?: ProbeResult.Failure(IOException("探测超过 $PROBE_HARD_TIMEOUT 没有返回"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.Failure(e)
        }

    private suspend fun applyResults(results: Map<Host, ProbeResult>) {
        val now = timeSource.markNow()
        val change = update(SwitchReason.PROBE, reselect = true) { snapshot ->
            snapshot.copy(
                hosts = snapshot.hosts.map { status ->
                    val result = results[status.host] ?: return@map status
                    status.copy(lastProbe = result, lastProbeAt = now)
                },
            )
        }
        // 一条都没测通的一轮不算数：不刷新有效期，下次读取线路时（受节流限制）会再试
        if (results.values.any { it is ProbeResult.Success }) lastRoundAt = now
        emit(AutoHostEvent.ProbeFinished(change.new.hosts))
        persist(change.new)
    }

    // endregion

    // region 缓存

    private fun restore(hosts: List<Host>): AutoHostState {
        val fallback = AutoHostState(hosts.first(), hosts.map(::HostStatus), pinned = null, isProbing = false)
        val snapshot = try {
            store.read()
        } catch (e: IOException) {
            emit(AutoHostEvent.CacheDiscarded(e))
            store.clear()
            null
        }
        if (snapshot == null || snapshot.current !in hosts) return fallback

        val age = (wallClock() - snapshot.savedAtMillis).milliseconds
        emit(AutoHostEvent.CacheRestored(snapshot.current, age))
        // 墙上时钟可能被用户改过：年龄为负说明时间不可信，和过期一样只恢复线路、不恢复测量结果
        if (age.isNegative() || age > probeTtl) return fallback.copy(current = snapshot.current)

        val probedAt = timeSource.markNow() - age
        lastRoundAt = probedAt
        return fallback.copy(
            current = snapshot.current,
            hosts = hosts.map { host ->
                val latency = snapshot.latencies[host] ?: return@map HostStatus(host)
                HostStatus(host, ProbeResult.Success(latency), probedAt)
            },
        )
    }

    private suspend fun persist(snapshot: AutoHostState) {
        val latencies = LinkedHashMap<Host, kotlin.time.Duration>()
        for (status in snapshot.hosts) {
            val probe = status.lastProbe
            if (probe is ProbeResult.Success) latencies[status.host] = probe.latency
        }
        try {
            withContext(ioDispatcher) {
                store.write(Snapshot(wallClock(), snapshot.current, latencies))
            }
        } catch (e: IOException) {
            emit(AutoHostEvent.CacheWriteFailed(e))
        }
    }

    // endregion

    private class Change(val old: AutoHostState, val new: AutoHostState) {
        val switched: Boolean get() = old.current != new.current
    }

    /**
     * 所有状态变更的唯一入口。[transform] 和策略都可能因为 CAS 失败被重复执行，所以事件在成功之后才发。
     */
    private inline fun update(
        reason: SwitchReason? = null,
        reselect: Boolean = false,
        transform: (AutoHostState) -> AutoHostState,
    ): Change {
        var strategyError: Throwable?
        var old: AutoHostState
        var new: AutoHostState
        do {
            strategyError = null
            old = _state.value
            new = transform(old)
            if (reselect && new.pinned == null) {
                val picked = try {
                    strategy.select(new.hosts, old.current)
                } catch (e: Exception) {
                    strategyError = e
                    null
                }
                val hosts = new.hosts
                val valid = picked?.takeIf { candidate -> hosts.any { it.host == candidate } }
                if (picked != null && valid == null) {
                    strategyError = IllegalStateException("策略返回了不在列表里的线路 $picked")
                }
                new = new.copy(
                    current = valid ?: old.current.takeIf { c -> hosts.any { it.host == c } } ?: hosts.first().host,
                )
            }
        } while (!_state.compareAndSet(old, new))

        strategyError?.let { emit(AutoHostEvent.StrategyFailed(it)) }
        val change = Change(old, new)
        if (change.switched && reason != null) emit(AutoHostEvent.HostSwitched(old.current, new.current, reason))
        return change
    }

    private fun findHost(url: String): Host? {
        val parsed = ParsedUrl.parse(url) ?: return null
        return _state.value.hosts.firstOrNull { it.host.matches(parsed.httpUrl) }?.host
    }

    private fun isOffline(): Boolean = networkMonitorStarted && networkMonitor.isOffline()

    private fun emit(event: AutoHostEvent) {
        val target = listener ?: return
        try {
            target.onEvent(event)
        } catch (_: Exception) {
            // 见 AutoHostListener 的说明：监听器出错不能影响调用方的请求
        }
    }

    internal companion object {
        private val PROBE_HARD_TIMEOUT = 30.seconds
        private val openNames = HashSet<String>()

        fun open(
            name: String,
            config: AutoHostConfig,
            store: SnapshotStore,
            networkMonitor: NetworkMonitor,
            timeSource: TimeSource.WithComparableMarks,
        ): AutoHost {
            synchronized(openNames) {
                check(openNames.add(name)) { "名为 \"$name\" 的 AutoHost 还没有 close，不能重复创建" }
            }
            return try {
                RealAutoHost(config, store, networkMonitor, timeSource, onClose = {
                    synchronized(openNames) { openNames.remove(name) }
                })
            } catch (e: Throwable) {
                synchronized(openNames) { openNames.remove(name) }
                throw e
            }
        }

        fun parseHosts(hosts: List<String>): List<Host> {
            require(hosts.isNotEmpty()) { "线路列表不能为空" }
            return hosts.map(Host::parse).distinct()
        }
    }
}
