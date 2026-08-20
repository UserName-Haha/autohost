package io.github.usernamehaha.autohost.okhttp

import io.github.usernamehaha.autohost.AutoHost
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 把发往本组线路的请求改写到 [AutoHost.current]，并把请求结果回灌给 [autoHost]。
 * 作为应用拦截器添加（`addInterceptor`）。其他主机的请求原样放行。
 *
 * 这个类只用到了 [AutoHost] 的公开方法，为 Glide、WebView、WebSocket 写适配时可以照着它来。
 *
 * 请求失败时不会换线路重试，异常原样抛给调用方；调用方取消的请求不计入失败。
 *
 * @param failureCodes 哪些状态码算线路故障。默认只有网关类错误；500 通常是业务自身的错误，换线路也没用
 * @param matches 除了线路列表里的主机以外，还有哪些请求要改写到当前线路。
 * 典型情况是 Retrofit 的 baseUrl 写死了一个域名，而服务端下发的线路列表里没有它
 */
public class AutoHostInterceptor(
    private val autoHost: AutoHost,
    private val failureCodes: Set<Int> = setOf(502, 503, 504),
    private val matches: ((HttpUrl) -> Boolean)? = null,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val rewritten = rewrite(original.url)
        val request = if (rewritten == original.url) original else original.newBuilder().url(rewritten).build()
        val reportUrl = rewritten.toString()

        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!chain.call().isCanceled()) autoHost.reportFailure(reportUrl, e)
            throw e
        }
        if (response.code in failureCodes) {
            autoHost.reportFailure(reportUrl, IOException("HTTP ${response.code}"))
        } else {
            autoHost.reportSuccess(reportUrl)
        }
        return response
    }

    private fun rewrite(url: HttpUrl): HttpUrl {
        if (matches?.invoke(url) == true) {
            val target = autoHost.current
            return url.newBuilder()
                .host(target.name)
                .apply { target.port?.let(::port) }
                .build()
        }
        val text = url.toString()
        val rewritten = autoHost.rewrite(text)
        return if (rewritten == text) url else rewritten.toHttpUrl()
    }
}
