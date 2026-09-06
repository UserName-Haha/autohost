package io.github.usernamehaha.autohost

import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SelectionStrategyTest {
    private val time = TestTimeSource()
    private val a = Host.parse("a.com")
    private val b = Host.parse("b.com")
    private val c = Host.parse("c.com")

    private fun ok(host: Host, latency: Duration) = HostStatus(host, ProbeResult.Success(latency), time.markNow())
    private fun failed(host: Host) = HostStatus(host, ProbeResult.Failure(IOException()), time.markNow())

    @Test
    fun `没有任何探测结果时保持当前线路，没有当前线路时用第一条`() {
        val strategy = SelectionStrategy.lowestLatency()
        val hosts = listOf(HostStatus(a), HostStatus(b))
        assertEquals(b, strategy.select(hosts, b))
        assertEquals(a, strategy.select(hosts, null))
    }

    @Test
    fun `当前线路没有探测成功过时直接换到最快的`() {
        val strategy = SelectionStrategy.lowestLatency()
        assertEquals(c, strategy.select(listOf(HostStatus(a), ok(b, 200.milliseconds), ok(c, 100.milliseconds)), a))
        assertEquals(b, strategy.select(listOf(failed(a), ok(b, 900.milliseconds)), a))
    }

    @Test
    fun `差距没超过阈值不切换`() {
        val strategy = SelectionStrategy.lowestLatency(switchThreshold = 0.2)
        val hosts = listOf(ok(a, 100.milliseconds), ok(b, 85.milliseconds))
        assertEquals(a, strategy.select(hosts, a))
        assertEquals(b, strategy.select(listOf(ok(a, 100.milliseconds), ok(b, 79.milliseconds)), a))
    }

    @Test
    fun `阈值为 0 时总是选最快的`() {
        val strategy = SelectionStrategy.lowestLatency(switchThreshold = 0.0)
        assertEquals(b, strategy.select(listOf(ok(a, 100.milliseconds), ok(b, 99.milliseconds)), a))
    }

    @Test
    fun `当前线路已不在列表里时选最快的`() {
        val strategy = SelectionStrategy.lowestLatency()
        assertEquals(b, strategy.select(listOf(ok(a, 300.milliseconds), ok(b, 100.milliseconds)), c))
        assertEquals(a, strategy.select(listOf(HostStatus(a), HostStatus(b)), c))
    }

    @Test
    fun `priority 选第一条探测没有失败的线路`() {
        val strategy = SelectionStrategy.priority()
        assertEquals(a, strategy.select(listOf(ok(a, 900.milliseconds), ok(b, 10.milliseconds)), b))
        assertEquals(b, strategy.select(listOf(failed(a), HostStatus(b), ok(c, 10.milliseconds)), a))
        assertEquals(a, strategy.select(listOf(failed(a), failed(b)), b))
    }

    @Test
    fun `连续失败达到阈值的线路在冷却期内被跳过`() {
        val strategy = SelectionStrategy.lowestLatency(failureThreshold = 3, cooldown = 60.seconds)
        fun hosts(failures: Int) = listOf(
            HostStatus(a, ProbeResult.Success(10.milliseconds), time.markNow(), failures, time.markNow()),
            ok(b, 500.milliseconds),
        )
        assertEquals(a, strategy.select(hosts(failures = 2), a))
        val failing = hosts(failures = 3)
        assertEquals(b, strategy.select(failing, a))

        time += 59.seconds
        assertEquals(b, strategy.select(failing, a))
        // 冷却结束，重新参与选择
        time += 1.seconds
        assertEquals(a, strategy.select(failing, b))
    }

    @Test
    fun `所有线路都在失败时退回到全部线路里选`() {
        val strategy = SelectionStrategy.lowestLatency()
        val hosts = listOf(
            HostStatus(a, ProbeResult.Success(300.milliseconds), time.markNow(), 5, time.markNow()),
            HostStatus(b, ProbeResult.Success(100.milliseconds), time.markNow(), 5, time.markNow()),
        )
        assertEquals(b, strategy.select(hosts, a))
    }

    @Test
    fun `skipFailing 可以包装自定义策略`() {
        val alwaysFirst = SelectionStrategy { hosts, _ -> hosts.first().host }.skipFailing(failureThreshold = 1)
        val hosts = listOf(HostStatus(a, consecutiveFailures = 1, lastFailureAt = time.markNow()), HostStatus(b))
        assertEquals(b, alwaysFirst.select(hosts, a))
    }

    @Test
    fun `README 里的自定义策略示例`() {
        val preferPrimary = SelectionStrategy { hosts, _ ->
            fun latency(status: HostStatus) = (status.lastProbe as? ProbeResult.Success)?.latency
            val fastest = hosts.filter { latency(it) != null }.minByOrNull { latency(it)!! }
                ?: return@SelectionStrategy hosts.first().host
            val primary = hosts.firstOrNull { it.host.name == "a.com" }
            val primaryLatency = primary?.let(::latency)
            if (primaryLatency != null && primaryLatency - latency(fastest)!! < 300.milliseconds) primary.host else fastest.host
        }
        assertEquals(a, preferPrimary.select(listOf(HostStatus(a), HostStatus(b)), null))
        assertEquals(a, preferPrimary.select(listOf(ok(a, 350.milliseconds), ok(b, 100.milliseconds)), b))
        assertEquals(b, preferPrimary.select(listOf(ok(a, 450.milliseconds), ok(b, 100.milliseconds)), a))
        assertEquals(b, preferPrimary.select(listOf(failed(a), ok(b, 100.milliseconds)), a))
    }

    @Test
    fun `参数校验`() {
        assertThrows(IllegalArgumentException::class.java) { SelectionStrategy.lowestLatency(switchThreshold = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { SelectionStrategy.priority(failureThreshold = 0) }
        assertThrows(IllegalArgumentException::class.java) { SelectionStrategy.priority(cooldown = (-1).seconds) }
    }
}
