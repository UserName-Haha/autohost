package io.github.usernamehaha.autohost.internal

import io.github.usernamehaha.autohost.AutoHostEvent
import io.github.usernamehaha.autohost.AutoHostListener
import io.github.usernamehaha.autohost.Host
import io.github.usernamehaha.autohost.ProbeResult
import io.github.usernamehaha.autohost.Prober
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlinx.coroutines.delay

internal class FakeStore(var snapshot: Snapshot? = null) : SnapshotStore {
    var readError: IOException? = null
    var writeError: IOException? = null
    var cleared = false

    override fun read(): Snapshot? {
        readError?.let { throw it }
        return snapshot
    }

    override fun write(snapshot: Snapshot) {
        writeError?.let { throw it }
        this.snapshot = snapshot
    }

    override fun clear() {
        cleared = true
        snapshot = null
    }
}

internal class FakeNetworkMonitor(private val startError: Exception? = null) : NetworkMonitor {
    var offline = false
    var stopped = false
    private var onNetworkChanged: (() -> Unit)? = null

    override fun start(onNetworkChanged: () -> Unit) {
        startError?.let { throw it }
        this.onNetworkChanged = onNetworkChanged
    }

    override fun isOffline(): Boolean = offline

    override fun stop() {
        stopped = true
    }

    fun switchNetwork() {
        onNetworkChanged!!.invoke()
    }
}

/** 按脚本返回结果的探测器：延迟为 null 表示失败，探测耗时等于延迟本身。 */
internal class FakeProber(vararg latencies: Pair<String, Duration?>) : Prober {
    val latencies: MutableMap<Host, Duration?> = latencies.associate { Host.parse(it.first) to it.second }.toMutableMap()
    val probed = CopyOnWriteArrayList<Host>()

    operator fun set(host: String, latency: Duration?) {
        latencies[Host.parse(host)] = latency
    }

    override suspend fun probe(host: Host): ProbeResult {
        probed += host
        val latency = latencies.getValue(host) ?: return ProbeResult.Failure(IOException("unreachable"))
        delay(latency)
        return ProbeResult.Success(latency)
    }
}

internal class RecordingListener : AutoHostListener {
    val events = CopyOnWriteArrayList<AutoHostEvent>()

    override fun onEvent(event: AutoHostEvent) {
        events += event
    }

    inline fun <reified T : AutoHostEvent> all(): List<T> = events.filterIsInstance<T>()
}
