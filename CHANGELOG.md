# 更新日志

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循[语义化版本](https://semver.org/lang/zh-CN/)。
1.0 之前，次版本号的升级可能包含不兼容的变更，会在这里写明。

## 0.1.0 - 2026-09-19

首个版本。

- `AutoHost`：并发探测一组等价线路，按策略选出当前线路，结果持久化，冷启动直接恢复。
- `SelectionStrategy`：内置 `lowestLatency` 和 `priority`，`skipFailing` 为任意策略加上故障线路跳过。
- `Prober`：默认 HTTP 探测，可替换。
- `AutoHostInterceptor`：OkHttp 接入，改写主机并回灌请求结果。
- `reportSuccess` / `reportFailure`：供 WebView、WebSocket、图片加载等 OkHttp 之外的渠道回灌结果。
- `pin` / `unpin`、`updateHosts`、`AutoHostEvent`。
