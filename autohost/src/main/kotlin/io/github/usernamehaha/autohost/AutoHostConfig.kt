package io.github.usernamehaha.autohost

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [AutoHost.create] 的配置。除了 [hosts] 以外都有默认值。 */
public class AutoHostConfig internal constructor() {
    internal var hosts: List<String> = emptyList()

    /** 线路列表，格式见 [Host.parse]。没有缓存时第一条会被用作初始线路。 */
    public fun hosts(vararg hosts: String) {
        this.hosts = hosts.toList()
    }

    public fun hosts(hosts: List<String>) {
        this.hosts = hosts.toList()
    }

    public var strategy: SelectionStrategy = SelectionStrategy.lowestLatency()

    public var prober: Prober = Prober.http()

    /** 测量结果的有效期。过期后下一次读取线路时会触发后台探测；缓存超过这个时间也只恢复线路、不恢复测量结果。 */
    public var probeTtl: Duration = 10.minutes

    /**
     * 由"测量结果过期"和"请求失败"触发的探测之间的最小间隔。这两种触发来自请求路径，频率可能很高。
     * 网络切换、线路列表变更、`refresh()` 和 `probe()` 不受限制：它们意味着手里的测量结果已经作废。
     */
    public var minProbeInterval: Duration = 30.seconds

    /** 网络切换后是否重新探测。需要 App 已声明 `ACCESS_NETWORK_STATE`。 */
    public var probeOnNetworkChange: Boolean = true

    internal var listener: AutoHostListener? = null

    /**
     * 接收库内部的事件，不设置就完全静默。
     *
     * 做成函数而不是属性，是因为 Kotlin 只对函数参数做 SAM 转换：这样可以直接写 `listener { event -> ... }`。
     */
    public fun listener(listener: AutoHostListener) {
        this.listener = listener
    }
}
