package io.github.usernamehaha.autohost.internal

import io.github.usernamehaha.autohost.Host
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 解析后的 URL。HttpUrl 不认 ws/wss，解析前换成 http/https，输出时再换回来。 */
internal class ParsedUrl private constructor(
    val httpUrl: HttpUrl,
    private val webSocket: Boolean,
) {
    fun withHost(matched: Host, target: Host): String {
        val builder = httpUrl.newBuilder().host(target.name)
        when {
            target.port != null -> builder.port(target.port)
            // 原线路带显式端口、新线路不带：URL 上的端口属于原线路，回到 scheme 的默认端口
            matched.port != null -> builder.port(HttpUrl.defaultPort(httpUrl.scheme))
        }
        val rewritten = builder.build().toString()
        return if (webSocket) "ws" + rewritten.removePrefix("http") else rewritten
    }

    companion object {
        fun parse(url: String): ParsedUrl? {
            val webSocket = url.startsWith("ws://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true)
            val httpUrl = (if (webSocket) "http" + url.substring(2) else url).toHttpUrlOrNull() ?: return null
            return ParsedUrl(httpUrl, webSocket)
        }
    }
}

internal fun Host.matches(url: HttpUrl): Boolean =
    name == url.host && (port == null || port == url.port)
