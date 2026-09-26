# lan-agent 已知坑 / 排障经验

> 配合 `AGENTS.md` §1–§20 一起读 —— 每条坑对应到具体设计决策。
>
> 以下坑表中:
> - 视觉 / Compose / 主题相关 → 见 `AGENTS.md` §9 / §10 / §15
> - WebView 配置单源 → 见 `AGENTS.md` §12
> - 浮刷新按钮持久化 → 见 `AGENTS.md` §11
> - SSH PATH 兜底 → 见 `AGENTS.md` §7

## WebView / Compose 渲染

| 现象 | 排查 |
|------|------|
| `webView.setBackgroundColor(...)` 在 hardware-accelerated 下 no-op,WebView 周围还是白底 | 用 Compose `Box.background()` 画底色,WebView 设 TRANSPARENT(§9) |
| `useWideViewPort = false` + web 有 `<meta viewport>` | 页面按 980px 渲染元素过大;`useWideViewPort = true`(§5) |
| `(-8).dp` 负 Dp | `IllegalArgumentException` 闪退;用 `windowInsetsPadding(...)` + 正 padding |
| `IconButton.size(28.dp)` 强制 48dp | 浮刷新按钮变 48dp;用 `Box + Modifier.clickable`,不绕 IconButton 的 minimumInteractiveComponentSize(§11) |
| Android 11+ gesture bar 不消失 | 系统限制,只能 swipe 唤起后自动隐;要彻底隐需切 3-button nav |
| 状态栏被扣两次 | 根 Scaffold 的 `contentWindowInsets` 没关;外层 `contentWindowInsets = WindowInsets(0,0,0,0)`(§10) |
| 详情页内容被底栏顶掉一截 | 内层屏幕又加了一次 `navigationBarsPadding`;底栏常驻后 inset 已被扣过一次(0.14.2 已去) |
| 页面里的 `<input>` 点一下就**整页刷新**,键盘闪一下就没,一个字打不进去 | 挂在 `AndroidView` 上的 `.imePadding()` 让本屏**每帧 IME inset 变化都重跑 composable 函数体**,而 `webView.loadUrl(...)`(和客户端装配)被写在了函数体里 → 每帧重新加载一次。装配 + 首次加载必须收进 `LaunchedEffect(webView)`(0.18.1 修;实测同一 bug 在进场动画期间也会把页面连加载 8 遍) |
| `loadDataWithBaseURL` 的 HTML **图表/图片看不见,但图是解码成功的** | 和第 70 行同源:`height:100%` 因**缺 `<!DOCTYPE html>`** 落 quirks 模式而塌成 0(第 70 行是 WebView 未测量,这里是 quirks 布局,两者症状像但根因不同 —— 都在"父容器高度算不出来")。探针 `naturalWidth/Height` 与 `complete` 全对、`getBoundingClientRect()` 却是 `[宽, 0]` 就是这个。修:`<!DOCTYPE html>` + `img{position:fixed;...}`(包含块是视口,不依赖 `body` 高度)。⚠️ **桌面/模拟器 Chrome 打开同一份 HTML 完全正常**,拿浏览器当对照会误判"HTML 没问题"(0.19.2 修,见 `AGENTS.md` §19) |

## WebView 上传 / 文件选择

| 现象 | 排查 |
|------|------|
| OEM ROM `params.createIntent()` 没带 `FLAG_GRANT_READ_URI_PERMISSION` | 选图后 WebView 拿不到 bytes;手动构建 pickIntent + `Intent.createChooser`(§6) |
| WebView `onShowFileChooser` 对 content:// URI 转换不可靠 | FileReader.readAsDataURL 拿不到字节;走 `window.lanAgentAttachImages` bridge 注入 base64(§6) |

## WebView 后台保活

| 现象 | 排查 |
|------|------|
| Activity.onPause 冻结 WebView 网络栈 | 切走再回来 SSE / WebSocket 全断;`WebViewKeepAliveService`(detached WebView + dataSync foreground)+ `DisposableEffect(url)` 启停(§8) |
| 服务忘 stop 把电池榨干 | WebViewScreen 跳走但服务没收到 onDispose;30 分钟 `PARTIAL_WAKE_LOCK` acquire(timeout) 兜底(§8) |
| Android 14 (API 34) 启 dataSync 服务 SecurityException | `startForeground(2-arg)` 抛异常;用 3-arg `startForeground(NOTIF, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` |
| 通知 channel MIN 重要性被 MIUI 完全隐藏 | channel 用 IMPORTANCE_LOW(不算最小,不算骚扰) |
| 没声明 `FOREGROUND_SERVICE_DATA_SYNC` | API 34+ startForeground SecurityException;Manifest 加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` |

## 网络 / 主机配置

| 现象 | 排查 |
|------|------|
| zai 9201 listen localhost | 手机访问 `192.168.x.x:9201/instances` 拒连;`pnpm --filter @zn-ai/zai dev -- --lan` 重启 zai 绑 0.0.0.0 |
| Wi-Fi IP 变了 | 卡片 URL 全部失效;编辑模式 → 点卡 → 改 URL;或改 `data/Cards.kt` 重 build |
| SSH exit 127 (`command not found`) | `which zai` 在 Mac 上返回空;`npm i -g zai` 全局装,或 `~/.zshenv` 加 PATH(§7) |
| SSH 启动 zai 报 `EADDRINUSE` | 默认 9201 被另一个 supervisor 占;改 SSH host 配置的 `zaiPort`(9201 → 9202/9203),或停 supervisor |
| SSH host DataStore 损坏 | `adb shell pm clear io.github.hotmanxp.lanagent` 会同时清掉所有 DataStore;备份后再清 |

## 原生 Agent 会话(wire / SSE)

| 现象 | 排查 |
|------|------|
| 会话详情页整个打不开(反序列化错) | `TranscriptEntry.timestamp` 必须声明 `JsonElement?` —— transcript 里 system / queue-operation 是 ISO 字符串,其余是数字。声明成 `Long` 一条 system 就能让整页解码失败(§14) |
| SSE 连上后收不到权限/队列事件 | 不给 `/api/event` 传 `topics` —— 白名单没有 `prompt.approve` / `prompt.permission` / `queue.changed`(§14) |
| 重连后消息重复 | 服务端 `Last-Event-ID` 按 `eventId` 匹配、而 `id:` 行写的是 `seq`,永远 miss → 每次都全量 replay;客户端按 `seq` 单调去重(§14) |
| 退出会话页后日志里反复重连 | 后台线程永不退出,漏 socket;`awaitClose` 里必须同时置 `cancelled` 标志,循环/catch/退避三处都看它(§14) |
| 自己刚发的消息不显示 | 服务端 SSE 没有"用户消息"事件;`AgentStore.appendLocalUser()` 本地乐观追加(§14) |
| 工具卡之后的助手文本跑到工具卡前面 | 工具卡 upsert 时清掉流式气泡游标(`curTextIdx` / `curThinkIdx`)(§14) |
| 权限/审批卡点不掉 | `respondPending` 必须**无论成败**都 `clearPending()` —— 服务端对过期请求回 404(§14) |
| 打开大会话卡顿/OOM | transcript 整份 JSON 一次读入;实测有 13MB / ~1300 条消息的会话;工具输出已按 20k 字符截断入库(§14) |

## SSH 终端 / JSch

| 现象 | 排查 |
|------|------|
| **JSch 写 pty 远端收不到数据**(静默丢包) | `Channel.getOutputStream()` 不是幂等的 —— 每次调用都 new 一个带缓冲包装器。`val out = channel.outputStream` 取一次复用,不要每次重新拿(§16) |
| 交互模式软键盘弹出瞬间终端整片变白 | `imePadding()` 在 WebView 和输入行各加了一次 → ime 高度被扣两次;**只留输入行那一处**,WebView 靠 `weight(1f)` 被动收缩(§16) |
| WebView 在 Compose 里全黑(只有背景色) | factory 里 `loadUrl` 时 WebView 还没测量,0x0 → `html{height:100%}` 解析成 0;`doOnLayout { if (height > 0) loadUrl }` + 每次 `addOnLayoutChangeListener` 推物理尺寸(§16) |
| 注入 keyevent 到 xterm 看不到回显 | 误判 xterm 坏;先确认数据真出了 socket(看 `fake-sshd` 的 `recv` 日志),再看上层(§16) |

## 底栏 / 导航

| 现象 | 排查 |
|------|------|
| 底栏在详情页里消失 / 高亮错位 | 拿"当前路由"推导 tab;详情页路由匹配不到 tab;改成显式 `currentTab` 状态(§17) |
| 底栏图标选中时"跳"一下 | 每栏配了"实心 / 描边"两套 ImageVector;两态**同一个图标**,只换 tint / 字重 / 不透明度(§14.1) |
| 选择实例弹层后几个实例点不到 | 被屏幕底边切掉,ModalBottomSheet 内容默认不滚动;`Modifier.heightIn(max = 360.dp).verticalScroll(...)` + `skipPartiallyExpanded = true`(§18) |

## 「进行中」聚合(0.15.0 起无 UI 入口,数据层保留)

| 现象 | 排查 |
|------|------|
| 「进行中」区十几秒才出现 / 一直空 | 一张写错 IP 的卡片把整轮聚合拖到 readTimeout 之后;光加 `withTimeout` 没用,必须给 `AgentApi` 传 `callTimeoutMs`(0.14.0 加的参数) |
| 从会话详情返回后「进行中」区空白 | NavHost 销毁任务栏 composition;进程内 `ActiveTasksCache` 兜底;`LaunchedEffect` key 要能区分 null(没读到)和 emptyList(真没有) |

## DisplayFiles / 文件预览

| 现象 | 排查 |
|------|------|
| **DisplayFiles 卡片离开页面后丢了 size 与时间** | 服务端把 transcript 里的 tool_result 存成字面量 `'done'`,元数据只走一次 SSE 且 take-and-delete;`DisplayFilesCache`(进程内)兜"离开再回来";**冷启动后只剩路径,无解**(§20) |
| 预览图片被判成「过大」 | 服务端上限是 **1 MiB**(`PREVIEW_DEFAULT_MAX`),不是 512KB;`FILE_PREVIEW_MAX_BYTES` 必须与它一致 |

## 编辑态 / 拖拽

| 现象 | 排查 |
|------|------|
| 编辑态拖拽排序动的是别的条目 | 任务栏上方多了"进行中"区,卡片 LazyColumn 绝对下标 ≠ 卡片下标;`DraggableCardItem` 同时收 `index`(回调用)和 `listIndex`(命中用) |
| 任务栏打开落到了错的实例 / 会话 | 记住的实例下线时本来就会回落;先确认 `/api/instances` 里 `state` 是不是 `running`;匹配键是 baseUrl,IP 变了 = 换了实例 |
| 选择实例弹层里出现「Instances 实例管理」这种卡片标题、且状态全是离线 | 管理器 `/api/instances` 不可达,走了卡片兜底目录;先确认管理器可达 |

## 编译 / Kotlin 语言

| 现象 | 排查 |
|------|------|
| **Kotlin 文件里写 `/api/*` 导致整文件编译失败** | Kotlin **块注释可嵌套**: KDoc 里的 `/*`(比如 `` `/api/*` ``)会开嵌套注释,把本该闭合的 `*/` 吃掉;路径通配写成 `` `/api/…` ``,或放进 `//` 行注释 |
| 没在首页添加「实例管理」入口 Card | 点 Storage 按钮弹"未配置实例管理入口卡片"对话框;加一张 `url = "http://host:port/instances"` 的 Card(参考 `defaultCards` 第 0 张) |
| `down` 状态心跳超时但不到 3 分钟,"启动"按钮不可点 | `effectiveState()` 把超过 `STALE_THRESHOLD_MS = 3min` 的 `down` 视为 `stopped`(§3) |
