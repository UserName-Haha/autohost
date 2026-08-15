package io.github.usernamehaha.autohost

import io.github.usernamehaha.autohost.internal.LowestLatencyStrategy
import io.github.usernamehaha.autohost.internal.PriorityStrategy
import io.github.usernamehaha.autohost.internal.SkipFailingStrategy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 选路策略：根据各线路的事实决定用哪一条。
 *
 * 实现应当是纯函数，不做 I/O、不阻塞。它在事实发生变化时被调用（一轮探测结束、回灌了失败、
 * 线路列表变更、取消固定），不会在每个请求上调用；并发更新下同一份输入可能被调用多次。
 *
 * 抛出异常或返回了不在 [select] 入参 `hosts` 里的线路时，库保持当前线路不变并发出
 * [AutoHostEvent.StrategyFailed]。
 */
public fun interface SelectionStrategy {
    /**
     * @param hosts 全部线路的事实，非空，顺序与线路列表一致
     * @param current 当前线路；线路列表刚被替换时它可能已不在 [hosts] 里
     */
    public fun select(hosts: List<HostStatus>, current: Host?): Host

    public companion object {
        /**
         * 选探测延迟最低的线路，默认策略。
         *
         * @param switchThreshold 新线路要比当前线路快这个比例以上才切换，避免两条延迟接近的线路来回切。
         * 当前线路被跳过或探测失败时无条件切换
         * @param failureThreshold 见 [skipFailing]
         * @param cooldown 见 [skipFailing]
         */
        public fun lowestLatency(
            switchThreshold: Double = 0.2,
            failureThreshold: Int = 3,
            cooldown: Duration = 60.seconds,
        ): SelectionStrategy {
            require(switchThreshold in 0.0..1.0) { "switchThreshold 应在 0 到 1 之间，实际是 $switchThreshold" }
            return LowestLatencyStrategy(switchThreshold).skipFailing(failureThreshold, cooldown)
        }

        /**
         * 按线路列表的顺序，选第一条探测没有失败的线路。适合主线路有成本或合规优势、
         * 只在它不可用时才让位的场景。
         */
        public fun priority(
            failureThreshold: Int = 3,
            cooldown: Duration = 60.seconds,
        ): SelectionStrategy = PriorityStrategy.skipFailing(failureThreshold, cooldown)
    }
}

/**
 * 给策略加上"避开正在失败的线路"：连续失败达到 [failureThreshold] 次、且距上次失败不到 [cooldown] 的线路
 * 不会交给被包装的策略。所有线路都满足条件时，全部交给它，由它选最不坏的。
 *
 * 冷却结束后线路重新参与选择，但失败计数只有成功的请求才会清零，
 * 所以它再失败一次就会立刻被再次跳过，不需要重新累计。
 */
public fun SelectionStrategy.skipFailing(
    failureThreshold: Int = 3,
    cooldown: Duration = 60.seconds,
): SelectionStrategy {
    require(failureThreshold > 0) { "failureThreshold 必须大于 0，实际是 $failureThreshold" }
    require(cooldown >= Duration.ZERO) { "cooldown 不能为负，实际是 $cooldown" }
    return SkipFailingStrategy(this, failureThreshold, cooldown)
}
