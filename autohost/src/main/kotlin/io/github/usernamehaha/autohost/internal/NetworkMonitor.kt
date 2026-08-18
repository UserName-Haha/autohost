package io.github.usernamehaha.autohost.internal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process

internal interface NetworkMonitor {
    /**
     * 开始监听。拿不到网络状态时抛异常，之后 [isOffline] 恒为 false。
     *
     * @param onNetworkChanged 默认网络换成了另一个网络时回调；首次连上不算
     */
    fun start(onNetworkChanged: () -> Unit)

    /** 只有明确知道设备离线才返回 true，不确定时返回 false。 */
    fun isOffline(): Boolean

    fun stop()
}

@SuppressLint("MissingPermission") // 库不声明权限，start() 里运行时检查，没有就不启用
internal class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {
    private var connectivityManager: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun start(onNetworkChanged: () -> Unit) {
        val granted = context.checkPermission(
            Manifest.permission.ACCESS_NETWORK_STATE, Process.myPid(), Process.myUid(),
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) throw SecurityException("App 没有声明 ACCESS_NETWORK_STATE")

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: throw IllegalStateException("拿不到 ConnectivityManager")
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            private var lastNetwork: Network? = null

            override fun onAvailable(network: Network) {
                val previous = synchronized(this) { lastNetwork.also { lastNetwork = network } }
                // 注册后系统会立刻回调一次当前网络，那不是切换
                if (previous != null && previous != network) onNetworkChanged()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.registerDefaultNetworkCallback(networkCallback)
        } else {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, networkCallback)
        }
        connectivityManager = manager
        callback = networkCallback
    }

    override fun isOffline(): Boolean {
        val manager = synchronized(this) { connectivityManager } ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                manager.activeNetworkInfo?.isConnected != true
            }
        } catch (_: SecurityException) {
            // 个别机型在权限已授予时仍会抛出，当作不确定
            false
        }
    }

    @Synchronized
    override fun stop() {
        val manager = connectivityManager ?: return
        callback?.let {
            try {
                manager.unregisterNetworkCallback(it)
            } catch (_: IllegalArgumentException) {
                // 已经被注销
            }
        }
        connectivityManager = null
        callback = null
    }
}
