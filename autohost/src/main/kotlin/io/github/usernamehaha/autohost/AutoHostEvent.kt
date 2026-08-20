package io.github.usernamehaha.autohost

import kotlin.time.Duration

/**
 * 库内部发生的、值得记录的事情。每个事件的 `toString()` 都是可读的，可以直接打日志。
 *
 * 后续版本可能新增事件类型，`when` 请带上 `else` 分支。
 */
public sealed interface AutoHostEvent {

    public class ProbeStarted(public val trigger: ProbeTrigger) : AutoHostEvent {
        override fun toString(): String = "开始探测，触发原因：$trigger"
    }

    public class ProbeFinished(public val results: List<HostStatus>) : AutoHostEvent {
        override fun toString(): String =
            "探测结束：" + results.joinToString { "${it.host}=${it.lastProbe}" }
    }

    /** 探测没有进行，或者结果被丢弃，因为设备当前没有网络。 */
    public class ProbeSkippedOffline(public val trigger: ProbeTrigger) : AutoHostEvent {
        override fun toString(): String = "设备离线，跳过探测，触发原因：$trigger"
    }

    public class HostSwitched(
        public val from: Host,
        public val to: Host,
        public val reason: SwitchReason,
    ) : AutoHostEvent {
        override fun toString(): String = "线路切换 $from -> $to，原因：$reason"
    }

    public class HostsUpdated(public val hosts: List<Host>) : AutoHostEvent {
        override fun toString(): String = "线路列表更新：$hosts"
    }

    /** 被固定的线路已不在新的线路列表里，固定被自动取消。 */
    public class PinCleared(public val host: Host) : AutoHostEvent {
        override fun toString(): String = "固定的线路 $host 已被移出列表，取消固定"
    }

    public class CacheRestored(public val current: Host, public val age: Duration) : AutoHostEvent {
        override fun toString(): String = "从缓存恢复线路 $current，缓存生成于 $age 前"
    }

    /** 缓存读不出来或内容不合法，已丢弃，按没有缓存处理。 */
    public class CacheDiscarded(public val cause: Throwable) : AutoHostEvent {
        override fun toString(): String = "丢弃缓存：$cause"
    }

    public class CacheWriteFailed(public val cause: Throwable) : AutoHostEvent {
        override fun toString(): String = "缓存写入失败：$cause"
    }

    /** 策略抛了异常或返回了列表外的线路，本次保持当前线路不变。 */
    public class StrategyFailed(public val cause: Throwable) : AutoHostEvent {
        override fun toString(): String = "选路策略出错，保持当前线路：$cause"
    }

    /**
     * 拿不到网络状态（通常是 App 没有声明 `ACCESS_NETWORK_STATE`），
     * "网络切换后重新探测"和"离线时不探测、不计失败"两项功能不生效。
     */
    public class NetworkMonitorUnavailable(public val cause: Throwable?) : AutoHostEvent {
        override fun toString(): String = "无法监听网络状态，离线保护和网络切换探测不生效：$cause"
    }
}

public enum class ProbeTrigger {
    /** 测量结果超过了 `probeTtl`，或者还没有探测过。 */
    STALE,

    /** 回灌的失败让策略换了线路。 */
    FAILURE,
    NETWORK_CHANGED,
    HOSTS_UPDATED,

    /** 调用方调用了 `refresh()` 或 `probe()`。 */
    MANUAL,
}

public enum class SwitchReason {
    PROBE,
    FAILURE,
    HOSTS_UPDATED,
    PINNED,
    UNPINNED,
}

/**
 * 接收 [AutoHostEvent]。回调可能发生在任意线程，包括调用 [AutoHost.reportFailure] 的线程，不要在里面做耗时操作。
 *
 * 回调里抛出的异常会被丢弃：监听器出错不应该让业务请求失败。
 */
public fun interface AutoHostListener {
    public fun onEvent(event: AutoHostEvent)
}
