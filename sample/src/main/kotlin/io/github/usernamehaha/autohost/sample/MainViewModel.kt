package io.github.usernamehaha.autohost.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.usernamehaha.autohost.AutoHostState
import io.github.usernamehaha.autohost.Host
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

data class Ticker(val price: String? = null, val servedBy: String? = null, val error: String? = null)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SampleApp
    private val autoHost = app.autoHost

    val hostState: StateFlow<AutoHostState> = autoHost.state

    private val _ticker = MutableStateFlow(Ticker())
    val ticker: StateFlow<Ticker> = _ticker

    private val _brokenHosts = MutableStateFlow(emptySet<String>())
    val brokenHosts: StateFlow<Set<String>> = _brokenHosts

    private val _log = MutableStateFlow(emptyList<String>())
    val log: StateFlow<List<String>> = _log

    init {
        viewModelScope.launch {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US)
            app.events.collect { event ->
                _log.update { (listOf("${time.format(Date())}  $event") + it).take(MAX_LOG_LINES) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _ticker.value = fetchTicker()
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    /** 业务代码里的 URL 写死第一个域名，实际发到哪条线路由拦截器决定。 */
    private suspend fun fetchTicker(): Ticker = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://${SampleApp.BINANCE_HOSTS.first()}/api/v3/ticker/price?symbol=BTCUSDT")
            .build()
        try {
            app.apiClient.newCall(request).execute().use { response ->
                val servedBy = response.request.url.host
                if (!response.isSuccessful) return@use Ticker(servedBy = servedBy, error = "HTTP ${response.code}")
                Ticker(price = JSONObject(response.body!!.string()).getString("price"), servedBy = servedBy)
            }
        } catch (e: IOException) {
            Ticker(error = e.message ?: e.javaClass.simpleName)
        }
    }

    fun probe() {
        viewModelScope.launch { autoHost.probe() }
    }

    fun togglePin(host: Host) {
        if (autoHost.state.value.pinned == host) autoHost.unpin() else autoHost.pin(host)
    }

    fun toggleBroken(host: Host) {
        val broken = !app.faultInjector.isBroken(host.name)
        app.faultInjector.setBroken(host.name, broken)
        _brokenHosts.update { if (broken) it + host.name else it - host.name }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 2_000L
        const val MAX_LOG_LINES = 100
    }
}
