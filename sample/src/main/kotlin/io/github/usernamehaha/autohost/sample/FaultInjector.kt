package io.github.usernamehaha.autohost.sample

import java.net.ConnectException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 让发往指定主机的请求直接失败，用来在手机上模拟"某条线路不通"。
 * 业务请求和探测请求共用它，所以被标记的线路在两边都表现为故障。
 */
class FaultInjector : Interceptor {
    private val brokenHosts = ConcurrentHashMap.newKeySet<String>()

    fun setBroken(host: String, broken: Boolean) {
        if (broken) brokenHosts += host else brokenHosts -= host
    }

    fun isBroken(host: String): Boolean = host in brokenHosts

    override fun intercept(chain: Interceptor.Chain): Response {
        val host = chain.request().url.host
        if (host in brokenHosts) throw ConnectException("模拟故障：$host 不可达")
        return chain.proceed(chain.request())
    }
}
