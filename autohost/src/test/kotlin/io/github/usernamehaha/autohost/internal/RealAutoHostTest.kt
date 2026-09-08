package io.github.usernamehaha.autohost.internal

import io.github.usernamehaha.autohost.AutoHostConfig
import io.github.usernamehaha.autohost.AutoHostEvent
import io.github.usernamehaha.autohost.Host
import io.github.usernamehaha.autohost.ProbeResult
import io.github.usernamehaha.autohost.ProbeTrigger
import io.github.usernamehaha.autohost.Prober
import io.github.usernamehaha.autohost.SelectionStrategy
import io.github.usernamehaha.autohost.SwitchReason
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealAutoHostTest {
    private val a = Host.parse("a.com")
    private val b = Host.parse("b.com")
    private val c = Host.parse("c.com")

    private val store = FakeStore()
    private val network = FakeNetworkMonitor()
    private val listener = RecordingListener()
    private var wallClock = 1_000_000L

    private fun TestScope.autoHost(
        prober: Prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds),
        networkMonitor: NetworkMonitor = network,
        configure: AutoHostConfig.() -> Unit = {},
    ): RealAutoHost {
        val config = AutoHostConfig().apply {
            hosts("a.com", "b.com")
            this.prober = prober
            listener(this@RealAutoHostTest.listener)
            configure()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        return RealAutoHost(
            config, store, networkMonitor, testScheduler.timeSource,
            wallClock = { wallClock }, dispatcher = dispatcher, ioDispatcher = dispatcher,
        )
    }

    // region 冷启动与缓存

    @Test
    fun `没有缓存时立即可用第一条线路，探测完再切到最快的`() = runTest {
        val autoHost = autoHost()
        assertEquals(a, autoHost.current)
        assertFalse(autoHost.state.value.isProbing)

        runCurrent()
        assertTrue(autoHost.state.value.isProbing)
        assertEquals(a, autoHost.current)

        advanceUntilIdle()
        assertEquals(b, autoHost.current)
        assertFalse(autoHost.state.value.isProbing)
        assertEquals(ProbeTrigger.STALE, listener.all<AutoHostEvent.ProbeStarted>().single().trigger)
        val switched = listener.all<AutoHostEvent.HostSwitched>().single()
        assertEquals(Triple(a, b, SwitchReason.PROBE), Triple(switched.from, switched.to, switched.reason))
        autoHost.close()
    }

    @Test
    fun `探测结果会落盘，只保存探测成功的线路`() = runTest {
        val autoHost = autoHost(FakeProber("a.com" to null, "b.com" to 100.milliseconds))
        advanceUntilIdle()
        val snapshot = store.snapshot!!
        assertEquals(b, snapshot.current)
        assertEquals(mapOf(b to 100.milliseconds), snapshot.latencies)
        assertEquals(wallClock, snapshot.savedAtMillis)
        autoHost.close()
    }

    @Test
    fun `未过期的缓存直接恢复线路和测量结果，不立即探测`() = runTest {
        store.snapshot = Snapshot(wallClock - 60_000, b, mapOf(a to 300.milliseconds, b to 100.milliseconds))
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)

        assertEquals(b, autoHost.current)
        advanceUntilIdle()
        assertTrue(prober.probed.isEmpty())
        val status = autoHost.state.value.hosts.first { it.host == b }
        assertEquals(ProbeResult.Success(100.milliseconds), status.lastProbe)
        assertEquals(1.minutes, status.lastProbeAt!!.elapsedNow())
        assertEquals(1.minutes, listener.all<AutoHostEvent.CacheRestored>().single().age)

        // 缓存的年龄接着算：再过 9 分钟就到 probeTtl 了
        advanceTimeBy(9.minutes)
        autoHost.current
        advanceUntilIdle()
        assertEquals(listOf(a, b), prober.probed.sortedBy { it.name })
        autoHost.close()
    }

    @Test
    fun `过期的缓存只恢复线路，并立即重新探测`() = runTest {
        store.snapshot = Snapshot(wallClock - 11.minutes.inWholeMilliseconds, b, mapOf(b to 100.milliseconds))
        val autoHost = autoHost()
        assertEquals(b, autoHost.current)
        assertNull(autoHost.state.value.hosts.first { it.host == b }.lastProbe)
        runCurrent()
        assertTrue(autoHost.state.value.isProbing)
        autoHost.close()
    }

    @Test
    fun `时钟被回拨导致缓存年龄为负时按过期处理`() = runTest {
        store.snapshot = Snapshot(wallClock + 5_000, b, mapOf(b to 100.milliseconds))
        val autoHost = autoHost()
        assertEquals(b, autoHost.current)
        assertNull(autoHost.state.value.hosts.first { it.host == b }.lastProbe)
        autoHost.close()
    }

    @Test
    fun `缓存里的线路已不在列表中时忽略缓存`() = runTest {
        store.snapshot = Snapshot(wallClock, c, mapOf(c to 1.milliseconds))
        val autoHost = autoHost()
        assertEquals(a, autoHost.current)
        assertTrue(listener.all<AutoHostEvent.CacheRestored>().isEmpty())
        autoHost.close()
    }

    @Test
    fun `缓存损坏时丢弃并照常工作`() = runTest {
        store.readError = IOException("broken")
        val autoHost = autoHost()
        assertEquals(a, autoHost.current)
        assertTrue(store.cleared)
        assertEquals("broken", listener.all<AutoHostEvent.CacheDiscarded>().single().cause.message)
        advanceUntilIdle()
        assertEquals(b, autoHost.current)
        autoHost.close()
    }

    @Test
    fun `缓存写入失败只发事件，不影响选路`() = runTest {
        store.writeError = IOException("disk full")
        val autoHost = autoHost()
        advanceUntilIdle()
        assertEquals(b, autoHost.current)
        assertEquals("disk full", listener.all<AutoHostEvent.CacheWriteFailed>().single().cause.message)
        autoHost.close()
    }

    // endregion

    // region 探测调度

    @Test
    fun `同一时刻只有一轮探测`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        repeat(5) { autoHost.refresh() }
        val first = async { autoHost.probe() }
        val second = async { autoHost.probe() }
        advanceUntilIdle()
        assertEquals(2, prober.probed.size)
        assertEquals(first.await(), second.await())
        assertEquals(1, listener.all<AutoHostEvent.ProbeStarted>().size)
        autoHost.close()
    }

    @Test
    fun `各线路并发探测，一轮的耗时取决于最慢的那条`() = runTest {
        val autoHost = autoHost()
        advanceTimeBy(299.milliseconds)
        assertTrue(autoHost.state.value.isProbing)
        advanceTimeBy(2.milliseconds)
        assertFalse(autoHost.state.value.isProbing)
        autoHost.close()
    }

    @Test
    fun `测量结果过期后读取线路会触发探测`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()
        prober.probed.clear()

        advanceTimeBy(9.minutes)
        autoHost.current
        autoHost.rewrite("https://a.com/x")
        advanceUntilIdle()
        assertTrue(prober.probed.isEmpty())

        advanceTimeBy(2.minutes)
        autoHost.rewrite("https://a.com/x")
        advanceUntilIdle()
        assertEquals(2, prober.probed.size)
        autoHost.close()
    }

    @Test
    fun `一条都没测通的一轮不刷新有效期，按最小间隔重试`() = runTest {
        val prober = FakeProber("a.com" to null, "b.com" to null)
        val autoHost = autoHost(prober)
        advanceUntilIdle()
        assertEquals(a, autoHost.current)
        assertEquals(2, prober.probed.size)

        advanceTimeBy(29.seconds)
        autoHost.current
        advanceUntilIdle()
        assertEquals(2, prober.probed.size)

        advanceTimeBy(2.seconds)
        prober["b.com"] = 100.milliseconds
        autoHost.current
        advanceUntilIdle()
        assertEquals(4, prober.probed.size)
        assertEquals(b, autoHost.current)
        autoHost.close()
    }

    @Test
    fun `探测器抛异常记为该线路失败`() = runTest {
        val autoHost = autoHost(prober = { host ->
            if (host == a) error("boom") else ProbeResult.Success(100.milliseconds)
        })
        advanceUntilIdle()
        val status = autoHost.state.value.hosts.first { it.host == a }
        assertEquals("boom", (status.lastProbe as ProbeResult.Failure).cause.message)
        assertEquals(b, autoHost.current)
        autoHost.close()
    }

    @Test
    fun `一直不返回的探测会被强制超时，不会卡住后续探测`() = runTest {
        var hang = true
        val autoHost = autoHost(prober = { host ->
            if (hang && host == a) awaitCancellation()
            ProbeResult.Success(100.milliseconds)
        })
        advanceTimeBy(31.seconds)
        assertFalse(autoHost.state.value.isProbing)
        assertTrue(autoHost.state.value.hosts.first { it.host == a }.lastProbe is ProbeResult.Failure)

        hang = false
        val results = async { autoHost.probe() }
        advanceUntilIdle()
        assertTrue(results.await().all { it.lastProbe is ProbeResult.Success })
        autoHost.close()
    }

    @Test
    fun `调用方取消 probe 不影响这一轮探测`() = runTest {
        val autoHost = autoHost()
        val caller = launch { autoHost.probe() }
        runCurrent()
        caller.cancelAndJoin()
        advanceUntilIdle()
        assertEquals(b, autoHost.current)
        autoHost.close()
    }

    // endregion

    // region 回灌

    @Test
    fun `连续失败达到阈值后切换线路并触发探测`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()
        prober.probed.clear()
        advanceTimeBy(1.minutes)

        repeat(2) { autoHost.reportFailure("https://b.com/v1/ticker", IOException()) }
        assertEquals(b, autoHost.current)
        autoHost.reportFailure("wss://b.com/stream")
        assertEquals(a, autoHost.current)
        assertEquals(SwitchReason.FAILURE, listener.all<AutoHostEvent.HostSwitched>().last().reason)

        advanceUntilIdle()
        assertEquals(ProbeTrigger.FAILURE, listener.all<AutoHostEvent.ProbeStarted>().last().trigger)
        assertEquals(2, prober.probed.size)
        // b 的探测延迟仍然更低，但它还在冷却期内
        assertEquals(a, autoHost.current)
        autoHost.close()
    }

    @Test
    fun `成功的请求清零失败计数`() = runTest {
        val autoHost = autoHost()
        advanceUntilIdle()
        repeat(2) { autoHost.reportFailure("https://b.com/") }
        autoHost.reportSuccess("https://b.com/")
        repeat(2) { autoHost.reportFailure("https://b.com/") }
        assertEquals(b, autoHost.current)
        assertEquals(2, autoHost.state.value.hosts.first { it.host == b }.consecutiveFailures)
        autoHost.close()
    }

    @Test
    fun `无法解析或不属于本组线路的 URL 被忽略`() = runTest {
        val autoHost = autoHost()
        advanceUntilIdle()
        val before = autoHost.state.value
        for (url in listOf("", "not a url", "ftp://b.com/", "https://other.com/", "b.com")) {
            autoHost.reportFailure(url)
            autoHost.reportSuccess(url)
        }
        assertEquals(before, autoHost.state.value)
        autoHost.close()
    }

    @Test
    fun `离线时的失败不计数`() = runTest {
        val autoHost = autoHost()
        advanceUntilIdle()
        network.offline = true
        repeat(10) { autoHost.reportFailure("https://b.com/") }
        assertEquals(0, autoHost.state.value.hosts.first { it.host == b }.consecutiveFailures)
        assertEquals(b, autoHost.current)
        autoHost.close()
    }

    // endregion

    // region 网络

    @Test
    fun `离线时不探测`() = runTest {
        network.offline = true
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()
        assertTrue(prober.probed.isEmpty())
        assertEquals(autoHost.state.value.hosts, autoHost.probe())
        assertEquals(2, listener.all<AutoHostEvent.ProbeSkippedOffline>().size)
        autoHost.close()
    }

    @Test
    fun `探测途中断网，这一轮的结果作废`() = runTest {
        val autoHost = autoHost()
        runCurrent()
        network.offline = true
        advanceUntilIdle()
        assertTrue(autoHost.state.value.hosts.all { it.lastProbe == null })
        assertTrue(listener.all<AutoHostEvent.ProbeFinished>().isEmpty())
        autoHost.close()
    }

    @Test
    fun `网络切换后立即重新探测，不受最小间隔限制`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()

        prober["a.com"] = 50.milliseconds
        network.switchNetwork()
        advanceUntilIdle()
        assertEquals(a, autoHost.current)
        assertEquals(ProbeTrigger.NETWORK_CHANGED, listener.all<AutoHostEvent.ProbeStarted>().last().trigger)
        autoHost.close()
    }

    @Test
    fun `探测途中网络切换，结束后再来一轮`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        runCurrent()
        network.switchNetwork()
        advanceUntilIdle()
        assertEquals(4, prober.probed.size)
        assertEquals(
            listOf(ProbeTrigger.STALE, ProbeTrigger.NETWORK_CHANGED),
            listener.all<AutoHostEvent.ProbeStarted>().map { it.trigger },
        )
        autoHost.close()
    }

    @Test
    fun `关闭 probeOnNetworkChange 后网络切换不触发探测`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober) { probeOnNetworkChange = false }
        advanceUntilIdle()
        network.switchNetwork()
        advanceUntilIdle()
        assertEquals(2, prober.probed.size)
        autoHost.close()
    }

    @Test
    fun `拿不到网络状态时发事件，其余功能照常`() = runTest {
        val monitor = FakeNetworkMonitor(startError = SecurityException("no permission"))
        monitor.offline = true
        val autoHost = autoHost(networkMonitor = monitor)
        advanceUntilIdle()
        assertEquals(b, autoHost.current)
        assertEquals(1, listener.all<AutoHostEvent.NetworkMonitorUnavailable>().size)
        autoHost.close()
        assertFalse(monitor.stopped)
    }

    // endregion

    // region 线路列表与固定

    @Test
    fun `更新线路列表保留仍在列表里的线路的事实，并探测新列表`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds, "c.com" to 20.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()

        autoHost.updateHosts(listOf("c.com", "b.com", "B.com"))
        val state = autoHost.state.value
        assertEquals(listOf(c, b), state.hosts.map { it.host })
        assertEquals(ProbeResult.Success(100.milliseconds), state.hosts[1].lastProbe)
        assertEquals(b, state.current)

        advanceUntilIdle()
        assertEquals(c, autoHost.current)
        assertEquals(listOf(c, b), listener.all<AutoHostEvent.HostsUpdated>().single().hosts)
        autoHost.close()
    }

    @Test
    fun `传入和当前相同的线路列表时什么都不做`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()
        repeat(3) { autoHost.updateHosts(listOf("a.com", "B.com")) }
        advanceUntilIdle()
        assertEquals(2, prober.probed.size)
        assertTrue(listener.all<AutoHostEvent.HostsUpdated>().isEmpty())
        autoHost.close()
    }

    @Test
    fun `网络反复抖动时探测串行进行，不会并发堆积`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        runCurrent()
        repeat(10) { network.switchNetwork() }
        advanceUntilIdle()
        // 进行中的一轮 + 结束后补的一轮
        assertEquals(4, prober.probed.size)
        autoHost.close()
    }

    @Test
    fun `当前线路被移出列表时立即换线路`() = runTest {
        val autoHost = autoHost(FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds, "c.com" to 900.milliseconds))
        advanceUntilIdle()
        autoHost.updateHosts(listOf("c.com", "a.com"))
        assertEquals(a, autoHost.current)
        assertEquals(SwitchReason.HOSTS_UPDATED, listener.all<AutoHostEvent.HostSwitched>().last().reason)
        autoHost.close()
    }

    @Test
    fun `探测途中更新线路列表，结束后再探测新列表`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds, "c.com" to 20.milliseconds)
        val autoHost = autoHost(prober)
        runCurrent()
        autoHost.updateHosts(listOf("b.com", "c.com"))
        advanceUntilIdle()
        assertEquals(listOf(a, b, b, c), prober.probed.toList())
        assertEquals(c, autoHost.current)
        autoHost.close()
    }

    @Test
    fun `线路列表不合法时抛异常且不改变状态`() = runTest {
        val autoHost = autoHost()
        val before = autoHost.state.value
        assertThrows(IllegalArgumentException::class.java) { autoHost.updateHosts(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { autoHost.updateHosts(listOf("b.com", "https://c.com")) }
        assertEquals(before, autoHost.state.value)
        autoHost.close()
    }

    @Test
    fun `固定线路期间不自动切换也不自动探测，手动探测仍可用`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()
        prober.probed.clear()

        autoHost.pin(a)
        assertEquals(a, autoHost.current)
        assertEquals(a, autoHost.state.value.pinned)

        repeat(5) { autoHost.reportFailure("https://a.com/") }
        advanceTimeBy(20.minutes)
        autoHost.current
        network.switchNetwork()
        advanceUntilIdle()
        assertEquals(a, autoHost.current)
        assertTrue(prober.probed.isEmpty())

        val probing = async { autoHost.probe() }
        advanceUntilIdle()
        assertEquals(2, probing.await().size)
        assertEquals(a, autoHost.current)
        autoHost.close()
    }

    @Test
    fun `取消固定后立即按策略选路`() = runTest {
        val autoHost = autoHost()
        advanceUntilIdle()
        autoHost.pin(a)
        autoHost.unpin()
        assertEquals(b, autoHost.current)
        assertNull(autoHost.state.value.pinned)
        assertEquals(
            listOf(SwitchReason.PROBE, SwitchReason.PINNED, SwitchReason.UNPINNED),
            listener.all<AutoHostEvent.HostSwitched>().map { it.reason },
        )
        autoHost.close()
    }

    @Test
    fun `固定列表外的线路抛异常`() = runTest {
        val autoHost = autoHost()
        assertThrows(IllegalArgumentException::class.java) { autoHost.pin(c) }
        autoHost.close()
    }

    @Test
    fun `被固定的线路移出列表后自动取消固定`() = runTest {
        val autoHost = autoHost()
        advanceUntilIdle()
        autoHost.pin(a)
        autoHost.updateHosts(listOf("b.com", "c.com"))
        assertNull(autoHost.state.value.pinned)
        assertEquals(b, autoHost.current)
        assertEquals(a, listener.all<AutoHostEvent.PinCleared>().single().host)
        autoHost.close()
    }

    // endregion

    // region 策略出错

    @Test
    fun `策略抛异常时保持当前线路并发事件`() = runTest {
        val autoHost = autoHost { strategy = SelectionStrategy { _, _ -> error("bad strategy") } }
        advanceUntilIdle()
        assertEquals(a, autoHost.current)
        assertEquals("bad strategy", listener.all<AutoHostEvent.StrategyFailed>().first().cause.message)
        autoHost.close()
    }

    @Test
    fun `策略返回列表外的线路时保持当前线路`() = runTest {
        val autoHost = autoHost { strategy = SelectionStrategy { _, _ -> c } }
        advanceUntilIdle()
        assertEquals(a, autoHost.current)
        assertTrue(listener.all<AutoHostEvent.StrategyFailed>().isNotEmpty())
        autoHost.close()
    }

    @Test
    fun `策略出错且当前线路已被移除时退回列表第一条`() = runTest {
        val autoHost = autoHost { strategy = SelectionStrategy { _, _ -> error("bad strategy") } }
        autoHost.updateHosts(listOf("c.com", "b.com"))
        assertEquals(c, autoHost.current)
        autoHost.close()
    }

    // endregion

    // region 其他

    @Test
    fun `rewrite 只替换主机，保留 scheme、路径和查询`() = runTest {
        val autoHost = autoHost()
        advanceUntilIdle()
        assertEquals("https://b.com/v1/ticker?symbol=btc_usdt#top", autoHost.rewrite("https://a.com/v1/ticker?symbol=btc_usdt#top"))
        assertEquals("wss://b.com/stream", autoHost.rewrite("wss://a.com/stream"))
        assertEquals("ws://b.com:8080/stream", autoHost.rewrite("WS://a.com:8080/stream"))
        assertEquals("https://b.com/keep", autoHost.rewrite("https://b.com/keep"))
        assertEquals("https://other.com/x", autoHost.rewrite("https://other.com/x"))
        assertEquals("not a url", autoHost.rewrite("not a url"))
        autoHost.close()
    }

    @Test
    fun `带显式端口的线路按端口匹配，改写时端口跟着线路走`() = runTest {
        val autoHost = autoHost(FakeProber("a.com:8443" to 300.milliseconds, "b.com" to 100.milliseconds, "c.com:9443" to 10.milliseconds)) {
            hosts("a.com:8443", "b.com")
        }
        advanceUntilIdle()
        assertEquals("https://b.com/x", autoHost.rewrite("https://a.com:8443/x"))
        assertEquals("https://a.com/x", autoHost.rewrite("https://a.com/x"))

        autoHost.updateHosts(listOf("a.com:8443", "b.com", "c.com:9443"))
        advanceUntilIdle()
        assertEquals("https://c.com:9443/x", autoHost.rewrite("https://b.com/x"))
        autoHost.close()
    }

    @Test
    fun `监听器抛异常不影响调用方`() = runTest {
        val autoHost = autoHost { listener { error("listener bug") } }
        advanceUntilIdle()
        repeat(3) { autoHost.reportFailure("https://b.com/") }
        assertEquals(a, autoHost.current)
        autoHost.close()
    }

    @Test
    fun `关闭后保持最后的线路，其余操作不再有效果`() = runTest {
        val prober = FakeProber("a.com" to 300.milliseconds, "b.com" to 100.milliseconds)
        val autoHost = autoHost(prober)
        advanceUntilIdle()
        autoHost.close()
        autoHost.close()
        assertTrue(network.stopped)

        prober.probed.clear()
        repeat(5) { autoHost.reportFailure("https://b.com/") }
        autoHost.updateHosts(listOf("c.com"))
        autoHost.pin(a)
        autoHost.refresh()
        advanceTimeBy(20.minutes)
        assertEquals(b, autoHost.current)
        assertEquals("https://b.com/x", autoHost.rewrite("https://a.com/x"))
        assertEquals(autoHost.state.value.hosts, autoHost.probe())
        assertTrue(prober.probed.isEmpty())
    }

    @Test
    fun `探测途中关闭会取消探测`() = runTest {
        val autoHost = autoHost()
        val waiting = async { autoHost.probe() }
        runCurrent()
        autoHost.close()
        advanceUntilIdle()
        assertEquals(a, autoHost.current)
        assertFalse(autoHost.state.value.isProbing)
        assertEquals(2, waiting.await().size)
    }

    @Test
    fun `线路列表为空或不合法时创建失败`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { autoHost { hosts(emptyList()) } }
        assertThrows(IllegalArgumentException::class.java) { autoHost { hosts("https://a.com") } }
    }

    @Test
    fun `同名实例未关闭时不能重复创建`() = runTest {
        fun open() = RealAutoHost.open(
            "dup", AutoHostConfig().apply { hosts("a.com"); prober = FakeProber("a.com" to 1.milliseconds) },
            FakeStore(), FakeNetworkMonitor(), testScheduler.timeSource,
        )
        val first = open()
        assertThrows(IllegalStateException::class.java) { open() }
        first.close()
        open().close()

        // 创建失败不能占住名字
        assertThrows(IllegalArgumentException::class.java) {
            RealAutoHost.open("dup2", AutoHostConfig(), FakeStore(), FakeNetworkMonitor(), testScheduler.timeSource)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RealAutoHost.open("dup2", AutoHostConfig(), FakeStore(), FakeNetworkMonitor(), testScheduler.timeSource)
        }
    }

    // endregion
}
