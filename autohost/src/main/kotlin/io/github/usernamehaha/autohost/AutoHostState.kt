package io.github.usernamehaha.autohost

/** [AutoHost] 在某一时刻的不可变快照。 */
public class AutoHostState internal constructor(
    /** 当前使用的线路。 */
    public val current: Host,
    /** 全部线路的事实，顺序与线路列表一致。 */
    public val hosts: List<HostStatus>,
    /** 调用方通过 [AutoHost.pin] 固定的线路；未固定为 null。 */
    public val pinned: Host?,
    /** 是否有一轮探测正在进行。 */
    public val isProbing: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is AutoHostState &&
            other.current == current &&
            other.hosts == hosts &&
            other.pinned == pinned &&
            other.isProbing == isProbing

    override fun hashCode(): Int {
        var result = current.hashCode()
        result = 31 * result + hosts.hashCode()
        result = 31 * result + (pinned?.hashCode() ?: 0)
        result = 31 * result + isProbing.hashCode()
        return result
    }

    override fun toString(): String =
        "AutoHostState(current=$current, pinned=$pinned, isProbing=$isProbing, hosts=$hosts)"

    internal fun copy(
        current: Host = this.current,
        hosts: List<HostStatus> = this.hosts,
        pinned: Host? = this.pinned,
        isProbing: Boolean = this.isProbing,
    ): AutoHostState = AutoHostState(current, hosts, pinned, isProbing)
}
