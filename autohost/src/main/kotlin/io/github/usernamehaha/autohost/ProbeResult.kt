package io.github.usernamehaha.autohost

import kotlin.time.Duration

/** 对一条线路的一次探测结果。 */
public sealed interface ProbeResult {

    public class Success(public val latency: Duration) : ProbeResult {
        override fun equals(other: Any?): Boolean = other is Success && other.latency == latency
        override fun hashCode(): Int = latency.hashCode()
        override fun toString(): String = "Success($latency)"
    }

    public class Failure(public val cause: Throwable) : ProbeResult {
        // 异常没有值语义，按引用比较
        override fun equals(other: Any?): Boolean = other is Failure && other.cause === cause
        override fun hashCode(): Int = System.identityHashCode(cause)
        override fun toString(): String = "Failure(${cause.javaClass.simpleName}: ${cause.message})"
    }
}
