# AutoHost

[![CI](https://github.com/UserName-Haha/autohost/actions/workflows/ci.yml/badge.svg)](https://github.com/UserName-Haha/autohost/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/UserName-Haha/autohost.svg)](https://jitpack.io/#UserName-Haha/autohost)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Android 多线路自动择优与故障切换。给它一组等价的接入域名，它始终告诉你当前该用哪一个。

## 解决什么问题

面向海外用户的 App 通常有多个接入域名：主域名、备用域名、不同 CDN 的线路。某条线路在某些地区或运营商下会变慢甚至不通，
而且随时在变。客户端需要自己探测、选择、在出问题时切换。

这件事自己写并不难，难的是写对：

- 探测不能挡在首屏请求前面；
- 两条线路延迟接近时不能来回切，否则连接复用全部失效；
- 用户取消的请求、断网时的失败不能算到线路头上；
- 选路组件自己出问题（缓存损坏、探测全挂）时，业务请求不能跟着失败；
- 选出来的线路不只是 OkHttp 要用，图片、WebView、WebSocket 也要用。

AutoHost 把这些处理好，对外只暴露"当前线路是哪条"。

## 快速开始

在 `settings.gradle.kts` 里加上 JitPack：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

添加依赖：

```kotlin
dependencies {
    implementation("com.github.UserName-Haha:autohost:0.1.0")
}
```

创建实例，挂上拦截器：

```kotlin
val autoHost = AutoHost.create(context) {
    hosts("api.example.com", "api.example.net", "api.example.org")
}

val client = OkHttpClient.Builder()
    .addInterceptor(AutoHostInterceptor(autoHost))
    .build()
```

到这里就可以用了。Retrofit 的 `baseUrl` 照常写其中一个域名，业务代码不用改；
发往这三个域名的请求会被改写到当前线路，其他请求原样放行。

最低支持 Android 5.0（API 21）。依赖只有 OkHttp（4.12 及以上，兼容 5.x）和 kotlinx-coroutines。
不需要额外的混淆配置。

> 如果 App 声明了 `ACCESS_NETWORK_STATE`，AutoHost 会在网络切换后重新探测，并在离线时暂停探测和失败计数。
> 库本身不声明这个权限；没有它其余功能照常工作。

几点说明：

- `AutoHost` 是进程级的单例，在 `Application.onCreate` 或 DI 容器里创建一次。同名实例没有 `close()` 就再次创建会抛异常。
- 创建之后立刻可用，不需要等探测：没有缓存时先用列表里的第一条线路，探测在后台完成后自动切换；下次冷启动直接恢复上次的线路。
- `AutoHostInterceptor` 用 `addInterceptor` 添加，并且放在其他拦截器前面，这样日志、签名等拦截器看到的都是改写之后的主机。
- 默认对每条线路的 `https://<host>/` 发 GET，状态码小于 500 就算通。有轻量的 ping 接口的话，用 `prober = Prober.http(path = "/ping")` 换掉。

### 确认它在工作

库默认不输出任何日志。接入时先把事件打出来看一眼：

```kotlin
AutoHost.create(context) {
    hosts("api.example.com", "api.example.net", "api.example.org")
    listener { event -> Log.d("AutoHost", event.toString()) }
}
```

```
D AutoHost: 开始探测，触发原因：STALE
D AutoHost: 探测结束：api.example.com=Success(212ms), api.example.net=Success(87ms), api.example.org=Failure(SocketTimeoutException: timeout)
D AutoHost: 线路切换 api.example.com -> api.example.net，原因：PROBE
```

第二次启动会看到 `从缓存恢复线路 api.example.net，缓存生成于 3m 12s 前`，并且不再立即探测。

## 在 OkHttp 之外使用

拦截器只是一个适配器，核心是 `AutoHost` 本身：

```kotlin
// 直接取当前线路
val imageUrl = "https://${autoHost.current}/static/banner.png"

// 改写已有的 URL：主机属于本组线路就换成当前线路，否则原样返回
webView.loadUrl(autoHost.rewrite(h5Url))

// WebSocket 单独一组线路，建连结果回灌
val wsHosts = AutoHost.create(context, name = "ws") { hosts("ws.example.com", "ws.example.net") }
val url = wsHosts.rewrite("wss://ws.example.com/stream")
// 建连成功：wsHosts.reportSuccess(url)
// 建连失败：wsHosts.reportFailure(url, error)
```

WebSocket 线路和 HTTP 线路是同一批域名的话，直接共用一个实例即可：`rewrite` 只替换主机，`wss://` 会原样保留。
配合 [ws-market-client](https://github.com/UserName-Haha/ws-market-client) 使用时，把 `rewrite` 放进它的 `url { }` 里，每次重连都会取到当时的最优线路。

`reportSuccess` / `reportFailure` 不抛异常，URL 不属于本组线路时直接忽略，可以放在全局的网络回调里无条件调用。

线路选择页面：

```kotlin
autoHost.state.collect { state ->
    // state.current、state.pinned、state.isProbing
    // state.hosts：每条线路最近一次探测结果和连续失败次数
}

autoHost.pin(host)   // 用户手动选线路：不再自动切换和自动探测
autoHost.unpin()
autoHost.probe()     // "重新测速"按钮，挂起到这一轮探测结束
```

需要用远程开关整体关闭择优时，`pin` 住列表里的第一条线路即可，库里没有单独的"启用/禁用"。

线路列表由服务端下发时：

```kotlin
autoHost.updateHosts(listOf("api.example.com", "api-new.example.com"))
```

仍在新列表里的线路保留已有的测量结果。库不保存线路列表，来源和持久化由接入方负责。

## 配置项

```kotlin
AutoHost.create(context, name = "default") {
    hosts("api.example.com", "api.example.net")

    strategy = SelectionStrategy.lowestLatency(
        switchThreshold = 0.2,   // 新线路要快 20% 以上才切换
        failureThreshold = 3,    // 连续失败几次后跳过这条线路
        cooldown = 60.seconds,   // 跳过多久
    )

    prober = Prober.http(
        scheme = "https",
        path = "/",              // 建议换成服务端的轻量 ping 接口
        timeout = 3.seconds,
        client = null,           // 需要复用证书锁定、代理等设置时传入
        isHealthy = { code -> code < 500 },
    )

    probeTtl = 10.minutes            // 测量结果的有效期
    minProbeInterval = 30.seconds    // 自动探测的最小间隔
    probeOnNetworkChange = true

    listener { event -> Log.d("AutoHost", event.toString()) }   // 默认不设置，完全静默
}
```

上面写出来的都是默认值。`name` 用来区分多组线路，同时是缓存文件名。

| 拦截器参数 | 默认值 | 说明 |
|---|---|---|
| `failureCodes` | `502, 503, 504` | 哪些状态码算线路故障。500 通常是业务自身的错误，换线路也没用 |
| `matches` | `null` | 除线路列表里的主机以外，还有哪些请求要改写。用于 `baseUrl` 写死的域名不在下发列表里的情况 |

### 自定义策略

策略是一个纯函数：拿到全部线路的事实，返回要用的线路。

```kotlin
// 主域名优先，只有它比最快的线路慢 300ms 以上才让位
val preferPrimary = SelectionStrategy { hosts, _ ->
    fun latency(status: HostStatus) = (status.lastProbe as? ProbeResult.Success)?.latency
    val fastest = hosts.filter { latency(it) != null }.minByOrNull { latency(it)!! }
        ?: return@SelectionStrategy hosts.first().host
    val primary = hosts.firstOrNull { it.host.name == "api.example.com" }
    val primaryLatency = primary?.let(::latency)
    if (primaryLatency != null && primaryLatency - latency(fastest)!! < 300.milliseconds) primary.host else fastest.host
}

strategy = preferPrimary.skipFailing()   // 加上"避开正在失败的线路"
```

策略抛异常或返回了列表外的线路时，AutoHost 保持当前线路不变并发出 `StrategyFailed` 事件。

### 自定义探测

```kotlin
prober = Prober { host ->
    val start = TimeSource.Monotonic.markNow()
    try {
        connectWebSocket("wss://$host/stream")   // 你自己的建连逻辑，需要响应协程取消
        ProbeResult.Success(start.elapsedNow())
    } catch (e: IOException) {
        ProbeResult.Failure(e)
    }
}
```

## 设计说明

完整的说明在 [docs/design.md](docs/design.md)，这里列几个主要的取舍。

**测量与决策分离。** 库只记录事实：每条线路最近一次探测结果、连续失败次数、上次失败的时间。
"健康""拉黑"这些结论不在库里，而在 `SelectionStrategy` 里。默认策略跳过一条线路的条件是一个纯计算
（连续失败达到阈值且还在冷却期内），所以不存在"解除拉黑""全部拉黑后重置"之类的状态管理。
所有线路都在失败时，策略自然退回到全部线路里选最不坏的。

**冷启动不阻塞。** 没有缓存时立即使用列表里的第一条线路，探测在后台跑完再切换。
最初几个请求可能没走最优线路，换来的是选路永远不在首屏的关键路径上。

**不做请求重试。** 请求失败不代表服务端没收到，下单接口重放会造成重复提交；
只重试 GET 也会和 OkHttp 自带的重试、业务层的重试叠加。失败原样抛给调用方，AutoHost 只记一次失败，影响的是之后的请求。

**探测是懒触发的。** 没有定时器。测量结果过期后，下一次读取线路时才在后台探测；
另外的触发条件是失败导致换了线路、网络切换、线路列表变更。同一时刻只有一轮探测。App 闲置时没有任何网络活动。

**回灌只记成败，不记耗时。** 真实请求的耗时包含服务端处理时间，而且只有当前线路有样本，不能和探测值放在一起比。
线路排名始终用同一种探测请求的结果。

**选路器故障时直通。** 缓存损坏、探测全部失败、策略抛异常，`current` 和 `rewrite` 都照常返回可用的值，异常通过事件抛出。

## 局限

- **Cookie 不跟随线路。** OkHttp 的 `CookieJar` 按域名隔离，换线路后依赖 Cookie 的会话不会带过去。用请求头传 token 的鉴权不受影响。
- **证书锁定要覆盖所有线路。** 用了 `CertificatePinner` 的话，规则要包含每一条线路的域名，传给 `Prober.http` 的 client 也一样。
- **不支持多进程**共用同一个 `name`。
- **线路不能带 path 前缀**，只能是主机名加可选端口。
- **创建时同步读一次缓存文件。** 文件只有几百字节，但开了 StrictMode 磁盘读检测的话会被报出来。
- **失败计数只有成功的请求才会清零。** 一条线路被跳过、冷却结束后重新参与选择，这时它再失败一次就会立刻被再次跳过，
  不需要重新累计到阈值。这是有意的（半开状态），但也意味着很久以前的失败记录会让它对下一次失败更敏感。
- **Kotlin 优先。** API 用了 DSL、`Duration` 和挂起函数，从 Java 调用不方便。

## 路线图

- 缓存存储可替换（MMKV、DataStore）
- 按网络类型分别缓存测量结果
- 发布到 Maven Central

## Sample

`sample` 模块用 Binance 公开行情接口的六个等价域名演示：实时显示各线路延迟，可以对任意线路模拟故障、固定线路，
并查看完整的事件日志。

```
./gradlew :sample:installDebug
```

## License

[MIT](LICENSE)
