package io.github.usernamehaha.autohost.internal

import io.github.usernamehaha.autohost.Host
import io.github.usernamehaha.autohost.HostStatus
import io.github.usernamehaha.autohost.ProbeResult
import io.github.usernamehaha.autohost.SelectionStrategy
import kotlin.time.Duration

internal class SkipFailingStrategy(
    private val delegate: SelectionStrategy,
    private val failureThreshold: Int,
    private val cooldown: Duration,
) : SelectionStrategy {
    override fun select(hosts: List<HostStatus>, current: Host?): Host {
        val usable = hosts.filterNot(::isFailing)
        return delegate.select(usable.ifEmpty { hosts }, current)
    }

    private fun isFailing(status: HostStatus): Boolean {
        if (status.consecutiveFailures < failureThreshold) return false
        val lastFailureAt = status.lastFailureAt ?: return false
        return lastFailureAt.elapsedNow() < cooldown
    }
}

internal class LowestLatencyStrategy(private val switchThreshold: Double) : SelectionStrategy {
    override fun select(hosts: List<HostStatus>, current: Host?): Host {
        val currentStatus = hosts.firstOrNull { it.host == current }
        val fastest = hosts
            .filter { it.lastProbe is ProbeResult.Success }
            .minByOrNull { (it.lastProbe as ProbeResult.Success).latency }
        // 没有任何线路探测成功过：没有依据换线路
            ?: return (currentStatus ?: hosts.first()).host

        val currentLatency = (currentStatus?.lastProbe as? ProbeResult.Success)?.latency
            ?: return fastest.host
        val fastestLatency = (fastest.lastProbe as ProbeResult.Success).latency
        return if (fastestLatency < currentLatency * (1 - switchThreshold)) fastest.host else currentStatus.host
    }
}

internal object PriorityStrategy : SelectionStrategy {
    override fun select(hosts: List<HostStatus>, current: Host?): Host =
        (hosts.firstOrNull { it.lastProbe !is ProbeResult.Failure } ?: hosts.first()).host
}
