package io.github.usernamehaha.autohost.sample

import android.app.Application
import android.util.Log
import io.github.usernamehaha.autohost.AutoHost
import io.github.usernamehaha.autohost.AutoHostEvent
import io.github.usernamehaha.autohost.Prober
import io.github.usernamehaha.autohost.okhttp.AutoHostInterceptor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient

class SampleApp : Application() {
    val faultInjector = FaultInjector()

    lateinit var autoHost: AutoHost
        private set
    lateinit var apiClient: OkHttpClient
        private set

    private val _events = MutableSharedFlow<AutoHostEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<AutoHostEvent> = _events

    override fun onCreate() {
        super.onCreate()
        val baseClient = OkHttpClient.Builder()
            .addInterceptor(faultInjector)
            .build()

        // Binance 的公开行情接口本身就有多个等价域名，正好是这个库要解决的场景
        autoHost = AutoHost.create(this) {
            hosts(BINANCE_HOSTS)
            prober = Prober.http(path = "/api/v3/ping", client = baseClient)
            // 库默认不打任何日志，要不要打、打到哪里由接入方决定
            listener { event ->
                Log.d("AutoHost", event.toString())
                _events.tryEmit(event)
            }
        }

        apiClient = baseClient.newBuilder()
            // 放在最前面：后面的拦截器和故障注入看到的都是改写之后的主机
            .apply { interceptors().add(0, AutoHostInterceptor(autoHost)) }
            .build()
    }

    companion object {
        val BINANCE_HOSTS = listOf(
            "api.binance.com",
            "api1.binance.com",
            "api2.binance.com",
            "api3.binance.com",
            "api4.binance.com",
            "data-api.binance.vision",
        )
    }
}
