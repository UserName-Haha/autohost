package io.github.usernamehaha.autohost

import android.content.Context
import io.github.usernamehaha.autohost.internal.AndroidNetworkMonitor
import io.github.usernamehaha.autohost.internal.ElapsedRealtimeSource
import io.github.usernamehaha.autohost.internal.FileSnapshotStore
import io.github.usernamehaha.autohost.internal.RealAutoHost
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * 在一组等价的线路里始终给出当前最好的那一条。
 *
 * 所有方法都是线程安全的。读取类的方法不阻塞，可以在主线程和拦截器里调用。
 */
public interface AutoHost : Closeable {
    /**
     * 当前线路，永远有值。读取时如果测量结果已过期，会在后台启动一轮探测，读取本身不等待它。
     */
    public val current: Host

    public val state: StateFlow<AutoHostState>

    /**
     * 如果 [url] 的主机属于本组线路，把它换成当前线路，scheme、path、query 保持不变；否则原样返回。
     * 支持 http、https、ws、wss。
     */
    public fun rewrite(url: String): String

    /**
     * 回灌一次成功的请求，清零该线路的连续失败计数。
     *
     * 和 [reportFailure] 一样不抛异常：[url] 无法解析或不属于本组线路时直接忽略，
     * 可以放在全局的网络回调里无条件调用。
     */
    public fun reportSuccess(url: String)

    /**
     * 回灌一次失败的请求。什么算失败由调用方决定；调用方主动取消的请求不应该回灌。
     * 已知设备离线时的回灌会被忽略。
     */
    public fun reportFailure(url: String, cause: Throwable? = null)

    /**
     * 整体替换线路列表并触发探测。仍在新列表里的线路保留已有的事实。
     * 库不保存线路列表，下次启动仍以 [AutoHostConfig.hosts] 为准。
     *
     * @throws IllegalArgumentException 列表为空或含有不合法的主机名
     */
    public fun updateHosts(hosts: List<String>)

    /**
     * 固定使用 [host]，由调用方接管选路：固定期间不自动切换、不自动探测，[probe] 和 [refresh] 仍可用。
     * 不持久化。
     *
     * @throws IllegalArgumentException [host] 不在线路列表里
     */
    public fun pin(host: Host)

    /** 取消固定，立即按策略重新选路。 */
    public fun unpin()

    /** 在后台启动一轮探测并立即返回；已有探测在进行时不重复发起。 */
    public fun refresh()

    /** 启动一轮探测（或加入正在进行的那一轮）并挂起到结束，返回各线路最新的事实。 */
    public suspend fun probe(): List<HostStatus>

    /** 取消内部协程、注销网络监听。之后 [current] 和 [rewrite] 保持最后的值，其余方法不再有效果。 */
    override fun close()

    public companion object {
        /**
         * @param name 区分多组线路（例如 REST 一组、WebSocket 一组），同时是缓存文件名
         * @throws IllegalStateException 同名实例尚未 [close]
         * @throws IllegalArgumentException 线路列表为空或含有不合法的主机名
         */
        public fun create(
            context: Context,
            name: String = "default",
            configure: AutoHostConfig.() -> Unit,
        ): AutoHost {
            require(name.matches(Regex("[A-Za-z0-9_-]+"))) { "name 只能包含字母、数字、下划线和连字符，实际是 \"$name\"" }
            val appContext = context.applicationContext
            val config = AutoHostConfig().apply(configure)
            return RealAutoHost.open(
                name = name,
                config = config,
                store = FileSnapshotStore(File(appContext.noBackupFilesDir, "autohost/$name")),
                networkMonitor = AndroidNetworkMonitor(appContext),
                timeSource = ElapsedRealtimeSource,
            )
        }
    }
}
