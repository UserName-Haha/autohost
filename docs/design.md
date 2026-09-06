# AutoHost 设计说明

## 要解决的问题

面向海外用户的 App 通常有多个等价的接入域名：主域名、备用域名、不同 CDN 厂商的线路。
某条线路在特定地区或运营商下会变慢、被污染或直接不通，而且情况随时间和网络环境变化。
客户端需要在这组域名里始终用当前最好的那一条，并在它出问题时换掉。

各团队自研这套逻辑时常见的问题：

- 启动时探测完才放行请求，选路成了首屏延迟的一部分。
- 两条线路延迟接近时来回切换，连接复用全部失效。
- 调用方取消请求被当成线路故障，导致误切换。
- 断网时所有线路一起"故障"，统计被污染。
- 选路组件自己出问题（缓存损坏、探测全部失败）时，业务请求跟着失败。
- 选路结果只能通过网络库的拦截器生效，图片、WebView、WebSocket 用不上。

## 不做的事

- 不做 HTTPDNS，不接管 DNS 解析。
- 不管线路列表从哪来、怎么保存。写死、服务端下发还是两者合并，由接入方决定，库只接受一个列表。
- 不做请求重试。请求"失败"不代表服务端没收到，下单这类接口重放会造成重复提交；
  即使只重试 GET，也会和 OkHttp 自带的连接重试、业务层重试叠加放大请求量。失败原样抛给调用方，库只记录事实。
- 不做按接口粒度的路由。

## 分层

| 层 | 职责 | 扩展方式 |
|---|---|---|
| 测量 | 怎么测一条线路 | `Prober` 接口，默认 HTTP 探测 |
| 事实 | 每条线路最近一次探测结果、连续失败次数、距上次失败多久 | 库维护，通过 `HostStatus` 只读暴露 |
| 决策 | 选哪条线路，包括要不要避开正在失败的线路 | `SelectionStrategy` 接口，内置两个实现 |
| 触发 | 什么时候重新探测 | 参数 |

库负责事实的准确和并发安全，所有"该怎么选"的判断都在一个可替换的纯函数里，默认策略只是这个函数的一个实现。

核心里没有"健康""拉黑"这类状态。默认策略跳过一条线路的条件是一个纯计算：
连续失败次数达到阈值，且距上次失败还在冷却时间内。所有线路都被跳过时，策略在全部线路里选最不坏的，
这是计算的自然结果，不需要"解除拉黑""重置"之类的动作。

核心自己保证的只有两条，都是机制而不是策略：

- `pin` 是调用方的显式指令，优先级高于策略。
- 策略抛异常或返回了列表外的线路时，保持当前线路不变，并发出事件。

## 公开 API

```kotlin
public interface AutoHost : Closeable {
    public val current: Host
    public val state: StateFlow<AutoHostState>
    public fun rewrite(url: String): String

    public fun reportSuccess(url: String)
    public fun reportFailure(url: String, cause: Throwable? = null)

    public fun updateHosts(hosts: List<String>)
    public fun pin(host: Host)
    public fun unpin()

    public fun refresh()
    public suspend fun probe(): List<HostStatus>
}
```

四类操作：读取（`current` / `state` / `rewrite`）、回灌（`reportSuccess` / `reportFailure`）、
管理（`updateHosts` / `pin` / `unpin`）、探测（`refresh` / `probe`）。

`AutoHostInterceptor` 是建在这些公开 API 之上的适配器，没有用到任何内部接口。
接入方可以照着它为 Glide、WebView、WebSocket 写自己的适配。

## 关键决定

### Host 是纯主机名

`Host` 只有主机名和可选端口，不带 scheme。`rewrite` 只替换主机名和端口，scheme、path、query 原样保留，
所以同一组线路可以同时服务 https 和 wss。scheme 只有探测时才需要，归 `Prober.http(scheme = ...)` 管。

### 冷启动不阻塞

没有缓存时，`current` 立即返回列表里的第一条线路，探测在后台跑完再切换。
代价是最初几个请求可能没走最优线路，换来的是选路永远不会出现在首屏的关键路径上。
有未过期的缓存时直接恢复上次的选择。

### 选路器故障时直通

缓存损坏、探测全部失败、策略抛异常、实例已关闭，这些情况下 `current` 和 `rewrite` 仍然返回可用的值。
异常不吞掉，通过事件抛给 listener。

### 探测是懒触发的，没有定时器

触发探测的情况：

- 读取 `current` 或调用 `rewrite` 时发现测量结果超过 `probeTtl`。
- 失败回灌导致策略换了线路，说明手里的测量结果已经过时。
- 网络切换。
- 线路列表变更。
- 调用方主动调 `refresh()` / `probe()`。

前两种来自请求路径，频率可能很高，受 `minProbeInterval` 节流。网络切换和线路列表变更不节流：
它们意味着手里的测量结果已经作废，等 30 秒再测没有意义；`updateHosts` 传入和当前相同的列表时什么都不做。
同一时刻只有一轮探测在进行，探测途中再来的触发最多让它结束后补一轮，所以网络反复抖动时探测是一轮接一轮串行的，不会并发堆积。
App 闲置时没有任何网络活动，也不需要感知 Activity 生命周期。
代价是读取 `current` 有一个副作用（可能启动后台探测），但读取本身永远不阻塞。

### 回灌只记成败，不记耗时

真实请求的耗时包含服务端处理时间，而且只有当前线路有样本，和探测值不是同一种量，不能拿来排名。
"多慢算慢"又取决于具体接口，是业务判断。所以回灌只驱动连续失败计数，线路排名始终用同一种探测请求的结果来比。
接入方如果有自己的慢判定，可以调 `refresh()`。

### pin 表示调用方接管

pin 期间库不自动选路，也不自动发探测；手动 `probe()` 仍然可用，供线路选择页面显示延迟。
远程开关降级这类需求用 `pin(第一条线路)` 实现，库里不设"启用/禁用"的概念。
pin 不持久化，进程重启后失效。

### 断网保护

断网时所有线路都会失败，把这些失败计入统计会污染事实。
有 `ACCESS_NETWORK_STATE` 权限时，断网期间不探测、不计失败，网络切换后重新探测。
库不在 manifest 里声明这个权限，不替接入方的 App 加权限；没有权限时这两项功能关闭，并发事件告知。

### 切换阈值

`lowestLatency` 策略下，新线路要比当前线路快 20% 以上才切换；当前线路被跳过（正在失败或探测失败）时无条件切换。
换线路意味着连接池里的连接全部作废，为几毫秒的差距付这个代价不值得。

### 探测测的是完整建连成本

默认探测用独立的、不复用连接的 OkHttpClient，延迟包含 DNS、TCP、TLS。线路之间的差异主要就在这一段。
探测计时用单调时钟；缓存的新鲜度跨进程，只能用墙上时钟，时钟被回拨导致年龄为负时按过期处理。

### 并发模型

状态是不可变快照，放在 `MutableStateFlow` 里用 CAS 更新。`current` 和 `rewrite` 只读 `state.value`，无锁。
实例持有一个 `SupervisorJob` 作用域，`close()` 时取消。探测用 OkHttp 的 `enqueue` 包成挂起函数，
协程取消时同步取消 Call，不占用阻塞线程。

### 公开类型不用 data class

`copy` 和 `componentN` 会成为二进制兼容的负担：加一个字段就是破坏性变更。公开的只读类型手写 `equals` / `hashCode` / `toString`。

### 事件是唯一的日志出口

所有值得记日志的事情都是结构化的 `AutoHostEvent`，`toString()` 可读。不设 listener 就完全静默。
后续版本可能新增事件类型，接入方的 `when` 要带 `else`。

## 边界行为

- 同一个 `name` 的实例未 `close` 就再次 `create`，抛 `IllegalStateException`，避免两个实例写同一个缓存文件。
- `hosts()` 和 `updateHosts()` 传空列表或无法解析的主机名，抛 `IllegalArgumentException`。下发数据的校验是接入方的责任。
- `updateHosts` 移除了被 pin 的线路时，自动 unpin 并发事件。
- `reportSuccess` / `reportFailure` 不抛异常。URL 无法解析或不属于本组线路时直接忽略，可以放在全局网络回调里无条件调用。
- `close()` 之后 `current` 和 `rewrite` 继续返回关闭时的值，回灌和探测变成空操作。

## 工程形态

Android Library，minSdk 21。只依赖 OkHttp 和 kotlinx-coroutines，两者都以 `api` 方式暴露，因为公开 API 里出现了它们的类型。
OkHttp 按 4.12 编译，接入方用 4.x 或 5.x 都可以。

核心逻辑不碰 Android 类型，Android 相关的只有两个 internal 类：缓存文件（`noBackupFilesDir`，自定义行格式，不引入 JSON 库）和网络监听。
单测全部跑在 JVM 上，用 MockWebServer 和协程虚拟时间。

OkHttp 只在 `Prober.http` 和 `AutoHostInterceptor` 两处用到，放在 `okhttp` 子包里。
没有拆成 core 和 okhttp 两个 artifact：目标用户几乎都在用 OkHttp，拆开会让"一行依赖"变成两行，好处很少。
