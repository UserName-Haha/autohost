package io.github.usernamehaha.autohost

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 一条线路：主机名加可选端口，不含 scheme 和 path。
 *
 * 同一组线路可以同时服务 https 和 wss，所以 scheme 不属于线路本身；
 * 探测时用什么 scheme 由 [Prober] 决定。
 */
public class Host private constructor(
    /** 小写的主机名，IPv6 地址不带方括号。 */
    public val name: String,
    /** 显式指定的端口；为 null 表示跟随 URL 的 scheme 默认端口。 */
    public val port: Int?,
) {
    override fun equals(other: Any?): Boolean =
        other is Host && other.name == name && other.port == port

    override fun hashCode(): Int = 31 * name.hashCode() + (port ?: 0)

    override fun toString(): String {
        val literal = if (':' in name) "[$name]" else name
        return if (port == null) literal else "$literal:$port"
    }

    public companion object {
        /**
         * 解析 `api.example.com` 或 `api.example.com:8443` 形式的线路。
         *
         * @throws IllegalArgumentException 带了 scheme、path，或主机名不合法
         */
        public fun parse(value: String): Host {
            val text = value.trim()
            require(text.isNotEmpty() && "://" !in text && '/' !in text && '?' !in text && '#' !in text && '@' !in text) {
                "线路只能是主机名加可选端口，例如 api.example.com 或 api.example.com:8443，实际是 \"$value\""
            }
            // 借 HttpUrl 做主机名校验和规范化（大小写、IDN、IPv6）
            val url = requireNotNull("http://$text/".toHttpUrlOrNull()) { "无法解析的主机名 \"$value\"" }
            val hasPort = text.substringAfterLast(']').contains(':')
            return Host(url.host, if (hasPort) url.port else null)
        }
    }
}
