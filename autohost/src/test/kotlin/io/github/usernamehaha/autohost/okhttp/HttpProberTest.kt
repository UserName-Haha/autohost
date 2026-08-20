package io.github.usernamehaha.autohost.okhttp

import io.github.usernamehaha.autohost.Host
import io.github.usernamehaha.autohost.ProbeResult
import io.github.usernamehaha.autohost.Prober
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpProberTest {
    private val server = MockWebServer()
    private lateinit var host: Host

    @Before
    fun setUp() {
        server.start()
        host = Host.parse("${server.hostName}:${server.port}")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun prober(client: OkHttpClient? = null, path: String = "/") =
        Prober.http(scheme = "http", path = path, timeout = 1.seconds, client = client)

    @Test
    fun `探测成功返回延迟，请求打到指定路径`() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(100, TimeUnit.MILLISECONDS))
        val result = prober(path = "/ping?from=probe").probe(host)
        assertTrue(result.toString(), result is ProbeResult.Success && result.latency >= 100.milliseconds)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/ping?from=probe", request.path)
    }

    @Test
    fun `默认小于 500 的状态码都算通，5xx 算失败`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(503))
        val prober = prober()
        assertTrue(prober.probe(host) is ProbeResult.Success)
        val failure = prober.probe(host) as ProbeResult.Failure
        assertEquals("HTTP 503", failure.cause.message)
    }

    @Test
    fun `isHealthy 可以自定义`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val prober = Prober.http(scheme = "http", isHealthy = { it == 200 })
        assertTrue(prober.probe(host) is ProbeResult.Failure)
    }

    @Test
    fun `连不上记为失败`() = runBlocking {
        server.shutdown()
        assertTrue(prober().probe(host) is ProbeResult.Failure)
    }

    @Test
    fun `超时记为失败`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val result = withTimeout(5.seconds) { prober().probe(host) }
        assertTrue(result is ProbeResult.Failure)
    }

    @Test
    fun `不跟随重定向`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere"))
        assertTrue(prober().probe(host) is ProbeResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `每次探测都新建连接`() = runBlocking {
        repeat(2) { server.enqueue(MockResponse()) }
        val prober = prober()
        prober.probe(host)
        prober.probe(host)
        assertEquals(0, server.takeRequest().sequenceNumber)
        assertEquals(0, server.takeRequest().sequenceNumber)
    }

    @Test
    fun `协程取消时取消请求`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val prober = Prober.http(scheme = "http", timeout = 30.seconds)
        val probing = async { prober.probe(host) }
        delay(200)
        withTimeout(2.seconds) { probing.cancelAndJoin() }
        assertTrue(probing.isCancelled)
    }

    @Test
    fun `沿用传入 client 的设置，但去掉选路拦截器`() = runBlocking {
        server.enqueue(MockResponse())
        val other = MockWebServer().apply { start() }
        try {
            val autoHost = FakeAutoHost(Host.parse("${other.hostName}:${other.port}"), members = setOf(host))
            val client = OkHttpClient.Builder()
                .addInterceptor(AutoHostInterceptor(autoHost))
                .addInterceptor(Interceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("X-Base", "kept").build())
                })
                .build()
            assertTrue(prober(client).probe(host) is ProbeResult.Success)
            assertEquals("kept", server.takeRequest().getHeader("X-Base"))
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `参数校验`() {
        assertThrows(IllegalArgumentException::class.java) { Prober.http(scheme = "wss") }
        assertThrows(IllegalArgumentException::class.java) { Prober.http(path = "ping") }
        assertThrows(IllegalArgumentException::class.java) { Prober.http(timeout = 0.seconds) }
    }
}
