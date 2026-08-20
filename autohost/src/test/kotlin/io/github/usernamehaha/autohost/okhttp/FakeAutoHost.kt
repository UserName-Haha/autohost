package io.github.usernamehaha.autohost.okhttp

import io.github.usernamehaha.autohost.AutoHost
import io.github.usernamehaha.autohost.AutoHostState
import io.github.usernamehaha.autohost.Host
import io.github.usernamehaha.autohost.HostStatus
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 只实现拦截器用到的那几个方法，顺带证明拦截器没有依赖内部实现。 */
internal class FakeAutoHost(override var current: Host, private val members: Set<Host>) : AutoHost {
    val successes = CopyOnWriteArrayList<String>()
    val failures = CopyOnWriteArrayList<Pair<String, Throwable?>>()

    override fun rewrite(url: String): String {
        val parsed = url.toHttpUrl()
        if (members.none { it.name == parsed.host && (it.port == null || it.port == parsed.port) }) return url
        return parsed.newBuilder().host(current.name).apply { current.port?.let(::port) }.build().toString()
    }

    override fun reportSuccess(url: String) {
        successes += url
    }

    override fun reportFailure(url: String, cause: Throwable?) {
        failures += url to cause
    }

    override val state: StateFlow<AutoHostState> get() = throw UnsupportedOperationException()
    override fun updateHosts(hosts: List<String>) = throw UnsupportedOperationException()
    override fun pin(host: Host) = throw UnsupportedOperationException()
    override fun unpin() = throw UnsupportedOperationException()
    override fun refresh() = throw UnsupportedOperationException()
    override suspend fun probe(): List<HostStatus> = throw UnsupportedOperationException()
    override fun close() = Unit
}
