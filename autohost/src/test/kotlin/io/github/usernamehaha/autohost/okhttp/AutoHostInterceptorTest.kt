package io.github.usernamehaha.autohost.okhttp

import io.github.usernamehaha.autohost.Host
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoHostInterceptorTest {
    private val primary = MockWebServer()
    private val backup = MockWebServer()
    private lateinit var primaryHost: Host
    private lateinit var backupHost: Host
    private lateinit var autoHost: FakeAutoHost

    @Before
    fun setUp() {
        primary.start()
        backup.start()
        primaryHost = Host.parse("${primary.hostName}:${primary.port}")
        backupHost = Host.parse("${backup.hostName}:${backup.port}")
        autoHost = FakeAutoHost(backupHost, members = setOf(primaryHost, backupHost))
    }

    @After
    fun tearDown() {
        primary.shutdown()
        backup.shutdown()
    }

    private fun client(interceptor: AutoHostInterceptor = AutoHostInterceptor(autoHost)) =
        OkHttpClient.Builder().addInterceptor(interceptor).retryOnConnectionFailure(false).build()

    @Test
    fun `请求被改写到当前线路，方法、路径、请求体不变`() {
        backup.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder()
            .url(primary.url("/v1/order?symbol=btc_usdt"))
            .post("{}".toRequestBody())
            .build()
        client().newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }

        val received = backup.takeRequest()
        assertEquals("POST", received.method)
        assertEquals("/v1/order?symbol=btc_usdt", received.path)
        assertEquals("{}", received.body.readUtf8())
        assertEquals("${backup.hostName}:${backup.port}", received.getHeader("Host"))
        assertEquals(0, primary.requestCount)
        assertEquals(listOf(backup.url("/v1/order?symbol=btc_usdt").toString()), autoHost.successes)
    }

    @Test
    fun `已经指向当前线路的请求不改写，照常回灌`() {
        backup.enqueue(MockResponse())
        client().newCall(Request.Builder().url(backup.url("/x")).build()).execute().close()
        assertEquals(1, autoHost.successes.size)
    }

    @Test
    fun `其他主机的请求原样放行`() {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(MockResponse())
            client().newCall(Request.Builder().url(other.url("/img.png")).build()).execute().close()
            assertEquals(1, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `网关类状态码回灌失败，响应照常返回给调用方`() {
        backup.enqueue(MockResponse().setResponseCode(503))
        backup.enqueue(MockResponse().setResponseCode(500))
        val client = client()
        client.newCall(Request.Builder().url(primary.url("/")).build()).execute().use { assertEquals(503, it.code) }
        client.newCall(Request.Builder().url(primary.url("/")).build()).execute().use { assertEquals(500, it.code) }

        assertEquals("HTTP 503", autoHost.failures.single().second!!.message)
        assertEquals(1, autoHost.successes.size)
        assertEquals(2, backup.requestCount)
    }

    @Test
    fun `连接失败回灌失败并原样抛出，不换线路重试`() {
        backup.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertThrows(IOException::class.java) {
            client().newCall(Request.Builder().url(primary.url("/")).build()).execute()
        }
        assertEquals(backup.url("/").toString(), autoHost.failures.single().first)
        assertEquals(0, primary.requestCount)
    }

    @Test
    fun `调用方取消的请求不回灌`() {
        backup.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val call = client().newCall(Request.Builder().url(primary.url("/")).build())
        val done = CountDownLatch(1)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = done.countDown()
            override fun onResponse(call: Call, response: Response) = done.countDown()
        })
        backup.takeRequest(2, TimeUnit.SECONDS)
        call.cancel()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(autoHost.failures.isEmpty())
        assertTrue(autoHost.successes.isEmpty())
    }

    @Test
    fun `matches 命中的请求即使不在线路列表里也改写到当前线路`() {
        backup.enqueue(MockResponse())
        val interceptor = AutoHostInterceptor(autoHost, matches = { it.host == "legacy.example.com" })
        client(interceptor).newCall(Request.Builder().url("http://legacy.example.com/v1/x").build()).execute().close()
        assertEquals("/v1/x", backup.takeRequest().path)
    }

    @Test
    fun `failureCodes 可以自定义`() {
        backup.enqueue(MockResponse().setResponseCode(500))
        val interceptor = AutoHostInterceptor(autoHost, failureCodes = setOf(500))
        client(interceptor).newCall(Request.Builder().url(primary.url("/")).build()).execute().close()
        assertEquals(1, autoHost.failures.size)
    }
}
