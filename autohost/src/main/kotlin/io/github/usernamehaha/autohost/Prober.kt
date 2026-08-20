package io.github.usernamehaha.autohost

import io.github.usernamehaha.autohost.okhttp.HttpProber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

/**
 * 测量一条线路。默认实现是 [http]；也可以换成 WebSocket 握手、TCP 建连或业务自己的 ping 接口。
 *
 * 实现要响应协程取消，并在有限时间内返回。抛出的异常（取消除外）会被记为
 * [ProbeResult.Failure]；超过 30 秒没有返回的探测会被取消并记为失败。
 */
public fun interface Prober {
    public suspend fun probe(host: Host): ProbeResult

    public companion object {
        /**
         * 对 `scheme://host[:port]path` 发一个 GET，以拿到响应头的耗时作为延迟。
         *
         * 每次探测都新建连接，延迟包含 DNS、TCP 和 TLS，线路之间的差异主要就在这一段。
         *
         * @param path 建议换成服务端的轻量 ping 接口；默认的 `/` 对多数网关也能工作，因为默认只要求状态码小于 500
         * @param client 需要复用证书锁定、代理、自定义 DNS 等设置时传入。库会从它派生一个不带连接池的 client，
         * 并移除其中的 [io.github.usernamehaha.autohost.okhttp.AutoHostInterceptor]，否则所有探测都会被改写到当前线路上
         * @param isHealthy 哪些状态码算探测成功
         */
        public fun http(
            scheme: String = "https",
            path: String = "/",
            timeout: Duration = 3.seconds,
            client: OkHttpClient? = null,
            isHealthy: (code: Int) -> Boolean = { it < 500 },
        ): Prober {
            require(scheme == "https" || scheme == "http") { "scheme 只能是 http 或 https，实际是 \"$scheme\"" }
            require(path.startsWith("/")) { "path 必须以 / 开头，实际是 \"$path\"" }
            require(timeout.isPositive()) { "timeout 必须大于 0，实际是 $timeout" }
            return HttpProber(scheme, path, timeout, client, isHealthy)
        }
    }
}
