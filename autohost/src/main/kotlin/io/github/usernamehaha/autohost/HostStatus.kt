package io.github.usernamehaha.autohost

import kotlin.time.TimeMark

/**
 * 库记录的关于一条线路的全部事实。这里只有事实，没有"健康""拉黑"之类的结论，
 * 结论由 [SelectionStrategy] 根据这些事实得出。
 *
 * 构造函数是公开的，方便为自定义策略写单测。
 */
public class HostStatus(
    public val host: Host,
    /** 最近一次探测的结果；从未探测过为 null。 */
    public val lastProbe: ProbeResult? = null,
    /** 最近一次探测的完成时刻，用 `lastProbeAt.elapsedNow()` 得到距今多久。 */
    public val lastProbeAt: TimeMark? = null,
    /** 通过 [AutoHost.reportFailure] 回灌的连续失败次数，[AutoHost.reportSuccess] 会清零。 */
    public val consecutiveFailures: Int = 0,
    /** 最近一次回灌失败的时刻。 */
    public val lastFailureAt: TimeMark? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is HostStatus &&
            other.host == host &&
            other.lastProbe == lastProbe &&
            other.lastProbeAt == lastProbeAt &&
            other.consecutiveFailures == consecutiveFailures &&
            other.lastFailureAt == lastFailureAt

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + (lastProbe?.hashCode() ?: 0)
        result = 31 * result + (lastProbeAt?.hashCode() ?: 0)
        result = 31 * result + consecutiveFailures
        result = 31 * result + (lastFailureAt?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "HostStatus($host, lastProbe=$lastProbe, consecutiveFailures=$consecutiveFailures)"

    internal fun copy(
        lastProbe: ProbeResult? = this.lastProbe,
        lastProbeAt: TimeMark? = this.lastProbeAt,
        consecutiveFailures: Int = this.consecutiveFailures,
        lastFailureAt: TimeMark? = this.lastFailureAt,
    ): HostStatus = HostStatus(host, lastProbe, lastProbeAt, consecutiveFailures, lastFailureAt)
}
