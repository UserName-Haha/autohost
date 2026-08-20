package io.github.usernamehaha.autohost.okhttp

import io.github.usernamehaha.autohost.Host
import io.github.usernamehaha.autohost.ProbeResult
import io.github.usernamehaha.autohost.Prober
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class HttpProber(
    private val scheme: String,
    private val path: String,
    private val timeout: Duration,
    private val baseClient: OkHttpClient?,
    private val isHealthy: (Int) -> Boolean,
) : Prober {

    // 多数情况下 Prober 在 Application.onCreate 里创建，把建 client 的开销推迟到第一次探测
    private val client: OkHttpClient by lazy {
        (baseClient?.newBuilder() ?: OkHttpClient.Builder())
            // 不去掉的话，探测请求会被改写到当前线路，所有线路测出来都是同一个值
            .apply { interceptors().removeAll { it is AutoHostInterceptor } }
            // 不保留空闲连接：每次探测都要测到完整的建连成本，也不能让上一轮的连接影响这一轮
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .cache(null)
            .build()
    }

    override suspend fun probe(host: Host): ProbeResult {
        val request = Request.Builder().url("$scheme://$host$path".toHttpUrl()).build()
        val call = client.newCall(request)
        val start = TimeSource.Monotonic.markNow()
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    val latency = start.elapsedNow()
                    val code = response.code
                    response.close()
                    continuation.resume(
                        if (isHealthy(code)) ProbeResult.Success(latency)
                        else ProbeResult.Failure(IOException("HTTP $code")),
                    )
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resume(ProbeResult.Failure(e))
                }
            })
        }
    }
}
