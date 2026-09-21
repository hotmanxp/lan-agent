# AGENTS.md — `/Users/ethan/code/lan-agent/`

> **lan-agent** — Android App,把局域网内多个 opencc-web 实例入口收成卡片列表 + **原生**展示实例管理 API + **原生** Agent 会话(直连 `/api/agent/sessions` + `/api/event` SSE)+ SSH 一键启动 zai。配套工程 `/Users/ethan/code/opencc-web`,zai 需 `pnpm --filter @zn-ai/zai dev -- --lan` 启动。
>
> **关键里程碑**: 0.10.0 视觉对齐 WorkBuddy + 自研 Markdown;0.14.0 改底部五栏;0.15.0 任务栏直接是原生 Agent 工作区;0.16.0 DisplayFiles 文件卡片;0.16.1 文件预览改面板内 overlay(不占路由);0.17.0 `/` 命令面板 + Skill 候选;0.17.4 语音 401 自愈;0.18.0 底部任务栏显示后台任务 / 后台子代理;0.18.1 WebView 函数体副作用修复(点 /m 输入框不再整页刷新)。**当前 HEAD**: HEAD on `main` · **versionCode 73** · **versionName 0.18.1**。
>
> **独立顶级目录、独立 git 仓库**,不在 opencc-web monorepo 内。spec / plan 在 `docs/superpowers/{specs,plans}/`(0.6.0 之后已过期,仅作历史参考)。

## 文档索引

| 想看什么 | 看哪个文件 |
|---------|------------|
| 技术栈版本 / 目录结构 / 路由清单 | [`docs/agents/overview.md`](docs/agents/overview.md) |
| 已知坑 / 排障经验 | [`docs/agents/pitfalls.md`](docs/agents/pitfalls.md) |
| WorkBuddy accessToken / 真机探测 | `docs/superpowers/specs/2026-09-14-workbuddy-api-token-applicability.md` |
| 初始设计 spec / 10-task plan | `docs/superpowers/specs/2026-08-24-lan-agent-android-app-design.md` / `plans/2026-08-24-lan-agent-android-app.md` |
| 用户向验收清单 | `README.md` |

## 目标 / 非目标

### 目标

- 单 Activity + Jetpack Compose + Navigation Compose;**不发 release**,只 debug APK
- 首屏卡片列表(hardcode seed + DataStore 增删改)
- **原生实例管理**(`InstancesScreen`):直连 `/api/instances`,2.5s 轮询
- **原生 Agent 会话** + 详情:直连该实例 `/api/agent/*` + `/api/event` SSE,支持发消息 / 中断 / 队列 steer / 权限 / 问询 / 文档审核
- **WorkBuddy 视觉体系**(关 dynamicColor)+ 自研 Markdown 渲染
- 三种添加实例: 手动表单 / 目录选择器 / QR 扫码
- SSH 一键启动 zai(JSch + nohup/disown,自动探测端口)
- WebView 后台保活(dataSync foreground service + detached WebView)
- WebView 文件上传(系统选择器 → `window.lanAgentAttachImages` bridge 注入 base64)
- 浮刷新按钮可拖拽,位置持久化到独立 DataStore

### 非目标

- 不做账号 / 鉴权;不写 release 签名 / ProGuard
- **不写自动化测试**(手动验收为主;唯一例外是 `app/src/test/` 下的 JVM 单测,守住 wire 坑)
- 不引入 ViewModel / Room / Hilt(Compose state + DataStore 够用)
- 不做 iOS / 鸿蒙

## 关键设计决策

### 1. 入口数据 = seed + DataStore

`data/Cards.kt` 写死 5 张默认卡片(首张 `seed-instances` 指向 `http://$HOST:9201/instances`)。首次启动读 DataStore;无 key → 返回 `defaultCards`。改 `Cards.kt` 不影响已装用户(只有卸载重装才回到 seed)。

### 2. 底部五栏导航

5 栏(任务/实例/SSH/服务/设置)显示名与图标来自 `TabDestination` 枚举(`ui/BottomTabs.kt`,**单一事实来源**)。底栏常驻,详情页也显示并高亮所属栏。当前高亮是显式 `currentTab` 状态,不是从路由推导。路由清单与详细栏表见 [`docs/agents/overview.md`](docs/agents/overview.md)。

### 3. 原生实例管理屏

- 直连 `{baseUrl}/api/instances`,`repeatOnLifecycle(STARTED)` 包裹 2.5s 轮询
- `down` 超 3min 视作 `stopped`(对齐 web effectiveState),让"启动"按钮可点
- 三种创建: 手动表单 / 目录选择器 / QR 扫码(QR 不进 InstancesScreen)
- 见 `ui/InstancesScreen.kt` / `ui/InstanceCard.kt` / `data/InstancesApi.kt`

### 4. 网络

`AndroidManifest.xml` 设 `usesCleartextTraffic="true"`,`network_security_config.xml` 的 `base-config cleartextTrafficPermitted="true"`。IP 白名单对 /16 段不生效,所以直接全放行。

### 5. WebView 文件上传

手动构建 `pickIntent` + `Intent.createChooser(...)`,绕开 OEM ROM 的 `params.createIntent()` 缺 `FLAG_GRANT_READ_URI_PERMISSION` 问题。拿回 `content://` 后**优先**走 `window.lanAgentAttachImages` bridge(`ContentResolver.openInputStream` → base64 → `evaluateJavascript` 注入),不走 WebView 标准路径。见 `ui/WebViewScreen.kt` 的 `onShowFileChooser`。

### 6. SSH 启动 zai

模块: `model/SshHost.kt` + `data/SshRepository.kt` + `ssh/JschClient.kt` + `ssh/ZaiLauncher.kt` + `ssh/ZaiPortProbe.kt`。

**命令模板**(`ZaiLauncher.PATH_PREFIX` 兜底 sshd PATH 缺失): `export PATH="$HOME/.local/bin:$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"; source ~/.zshenv 2>/dev/null; source ~/.bashrc 2>/dev/null; nohup zai --lan --port ${zaiPort} > /tmp/zai.log 2>&1 & disown`。**全局 `zai` 二进制**,不走 `pnpm --filter`。每条 SSH host 独立 `zaiPort`(默认 9201),避免 `EADDRINUSE`。`StrictHostKeyChecking=no`(LAN 工具无 MITM 威胁模型)。密码存 DataStore 明文(Phase 2 接受)。

### 7. WebView 后台保活

`WebViewKeepAliveService`(`dataSync` foreground service)持一个未附到 View hierarchy 的 detached WebView — Chromium 跳过 rasterization 但 JS engine + 网络栈照跑。`WebViewScreen.DisposableEffect(url)` 启停。30 分钟 `PARTIAL_WAKE_LOCK` acquire(timeout) 兜底。API 34 必须 3-arg `startForeground(NOTIF, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` + Manifest `FOREGROUND_SERVICE_DATA_SYNC` 权限。通知 channel `webview_keepalive`(`IMPORTANCE_LOW` — MIUI 会隐藏 MIN)。

### 8. WebView 配置单源

`service/WebViewFactory.create(context, url)` 是 **`WebViewKeepAliveService` 与 `FileViewerOverlay` 用的工厂入口**;`WebViewScreen` 内联的 settings 与之 1:1 对齐,避免 foreground/background settings 漂移让 SSE 重连定时器悄悄重置。配置: `javaScriptEnabled` / `domStorageEnabled` / `useWideViewPort=true` / `loadWithOverviewMode=false` / `textZoom=85` / TRANSPARENT 背景。`createForContent` 单独把 `textZoom` 改回 100,给 FileViewerOverlay 用。

### 9. WebView 背景色陷阱

深色背景**必须在 Compose 层画**(`Box.background(...)`),WebView 的 `setBackgroundColor` 在 hardware-accelerated 下是 no-op。WebView 设 `TRANSPARENT`。

### 10. 系统栏 + inset

`WindowCompat.setDecorFitsSystemWindows(window, false)` + `WindowInsetsControllerCompat.hide(navigationBars())` + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`。状态栏保留可见,图标颜色随主题切(`isAppearanceLightStatusBars = !darkTheme`)。外层 Scaffold `contentWindowInsets = 0`,inset 全交给内层屏幕(否则状态栏被扣两次)。

### 11. 浮刷新按钮

`WebViewScreen` 右中浮一个 28dp `Box + clickable` 圆形刷新按钮(`IconButton` 会被 `minimumInteractiveComponentSize=48dp` 强制覆盖)。位置持久化到独立 `data/UiPrefsRepository.kt`(DataStore `lan_agent_ui_prefs`,key=`refresh_btn_x/y`)。**不要写到 CardRepository**(卡片 schema 演进会拖累 UI 偏好)。

### 12. 实例 app profile

`InstanceAppProfile { TaskFactory, Weixin }`(枚举名 `@SerialName` 映射到 `'task-factory'` / `'weixin'`)对齐 opencc-web `InstanceDefinition.app`。**`weixin` 不在创建表单露**(服务端自动管,只通过卡片 `WeixinTag` 显示);`task-factory` 有顶栏 `RocketLaunch` 快捷按钮。创建后 PATCH 不接受 `app`(只读显示)。`null` / 未知字符串都 400(`parseAppField`),所以 `InstancesApi.createInstance` 在 `app != null` 时才写 body,避免发字面 `null`。

### 13. 原生 Agent 会话

- 数据流三步(顺序不能换): `GET /api/agent/sessions/:id` → `GET /state` → `GET /api/event?sid=` SSE
- **transcript 时间戳双形态**(数字 / ISO 字符串),`TranscriptEntry.timestamp` 必须 `JsonElement?` + `EpochMsSerializer`,否则一条 system 就能让整页反序列化失败
- **SSE 三条血泪坑**(见 pitfalls.md): 不传 topics;按 seq 单调去重;`cancelled` 标志循环/catch/退避三处都看
- 工具卡 key 固定 `tool-<toolUseId>`,首次 upsert 清流式气泡游标(`curTextIdx` / `curThinkIdx`);用户消息**本地乐观追加**(`appendLocalUser`,SSE 没"用户消息"事件)
- 工具输出/入参**入库即截断**(`capForDisplay`,输出 20k / 入参 6k);图片附件**统一重编码 JPEG**(白底铺平,长边 1600 / Q85),`contentBlocks` 只能放图片块,**文本必须留顶层 `prompt`**;只发图合法,**不传** `prompt` 字段
- 单测 `app/src/test/.../data/AgentModelsTest.kt` 钉 wire 坑(浮点 mtime / 双形态 timestamp / tool_result 三形态)
- 见 `ui/AgentSessionScreen.kt` / `data/AgentSessionStore.kt` / `data/AgentApi.kt`

### 14. WorkBuddy 视觉体系 + Markdown

- **关 dynamicColor**;`surface` = 页底灰,卡片族 = 白(`Scaffold` / `Card` / `ModalBottomSheet` 一次性对齐)
- 亮色品牌 `#ff6600`,深色 `#35D6B6`;用户气泡中性浅灰 `#E2E4E3`(非品牌绿,WorkBuddy vs 绿色气泡 IM 的分水岭);发送钮禁用态 `#E0E3E8` → 本地 `LocalWbExtras`(`@Immutable data class WbExtras`,M3 槽位装不下)
- 输入条 = **双行白卡**(上排文本域 / 下排工具条),发送钮常驻只有颜色变(空输入 = 浅蓝灰禁用,有内容 = 品牌橙,运行中 = error 实心圆 + 停止图标)
- `ui/Markdown.kt` 自研: 解析/渲染分离(`MarkdownParser.parse` 是纯 Kotlin 函数,不 import Compose);**未闭合围栏直接当代码块渲染**(流式输出中间态);**找不到闭合标记的行内标记原样输出**(半截 `**` / 半截 `` ` ``);表格 `horizontalScroll` + `widthIn(min = 96.dp)`;链接用 `LinkAnnotation.Url` + `TextLinkStyles`,`Text` 自动走 `LocalUriHandler`
- 见 `ui/LanAgentTheme.kt` / `ui/Markdown.kt`;改色板 → `WbPalette`,改字号 → `WbTypography`,换机器人图 → 覆盖 `drawable-nodpi/wb_mascot.png`

### 15. SSH 终端 + 快捷命令(0.13.0)

- **命令模式**(默认): 每条走独立 `exec`,输出干净、带 exit code + 耗时、可停止;**交互模式**: `ChannelShell` + `setPty(true)`,WebView 跑 xterm.js;顶部 Terminal 图标切换,只关 pty(`exec` 继续可用)
- 快捷命令**全局共用一份**(`model/QuickCommand.kt`,DataStore `lan_agent_quick_commands`);`confirm=true` 弹二次确认;交互模式下写进 pty(`command + "\n"`);assets 打包 xterm.js 5.5.0 + addon-fit 0.10.0,页面无网络
- `SshShell` 线程: reader / writer 两条 daemon 读写分离;UI 线程只入队不碰 socket;UTF-8 用**状态化** `CharsetDecoder` 流式解码(3 字节 CJK 跨两次 8KB 读会被 `String(bytes, UTF_8)` 切两个 U+FFFD)
- 桥 `TerminalJsBridge`(`window.AndroidTerm`): `ready`/`send`/`resize`/`copy`/`hideIme`/`diag`,双向 base64;碰 Compose 状态 / View 的 `post` 回主线程
- 排障: `WebChromeClient.onConsoleMessage` + 页面 `__diag/__fatal/__metrics` → `adb logcat -s LanAgentTerm`
- 测试基建(`/tmp`,不进仓库): `fake-sshd.py`(paramiko sshd:2222,`test/test`,造 `utf8`/`bigout`/`slow`/`fail`/`sleep 60`/`cols`/`echo X`;⚠️ **没终端行规程**,Enter `\r` 不会 ICRNL 折 `\n`,分行要同时认 `\r` 和 `\n`)+ `ui.py`(adb UI driver)+ 纯 JVM `jsch-0.1.55.jar` 复现隔离 Android / Compose / WebView / IME

### 16. 底部五栏导航细节

- 视觉: 白底 + 顶部 0.5dp hairline,内容区 56dp(M3 NavigationBar 80dp 太肥);5 等分,每格 23dp 图标 + 3dp 间距 + 10sp label
- 选中 = 深色图标 + SemiBold 深色文字;未选中 = `onSurfaceVariant` 灰 + Regular + 图标 0.78 不透明度。**两态同一个 ImageVector**(不靠"实心/描边"换形状);不用 M3 indicator 药丸;去水波纹
- 图标一律 `Icons.Rounded`(Material Symbols Rounded);新增前先 `unzip -l classes.jar | grep rounded/<Name>Kt`(导入路径都是 `androidx.compose.material.icons.rounded.*`,带镜像语义的走 `.automirrored.rounded.*`)
- `material3 Icon(imageVector, …)` 没 `alpha` 参数,要压不透明度走 `Modifier.alpha(...)`
- tab 切换: `popUpTo(起始 tab) { saveState = true }` + `restoreState = true`,切走存整条返回栈 + 可保存状态;App 内跨栏跳转走 `selectTab`(`AppNavHost(onSelectTab = …)`),不要自己 `navigate`
- 「进行中」聚合(`data/ActiveTasks.kt`): 卡片去重 → 每实例 `listSessions()` → 留 30 分钟内更新的前 3 条 → 对最活跃 2 条再拉 `/state`;**只探前 2 条**避免 O(N·M);**每实例 2.5s callTimeout**(看门狗线程强制 cancel,`withTimeout` 在阻塞 socket 上没用);进程内 `ActiveTasksCache` 兜底(0.15.0 起无 UI 入口,文件保留)
- 主题切换: `UiPrefsRepository.ThemeMode` + MainActivity collect 后喂 `LanAgentTheme(darkTheme = …)`,即时全局生效,不需 `recreate()`;落盘存 `storageKey` 字符串不是 ordinal,认不出回落 `System`
- 远程服务栏: 探活**拿到任何 HTTP 响应就算在线**(含 401/404/500);离线用灰不用红

### 17. 任务栏 = 原生 Agent 工作区

- 一个屏两个入口: tab 根(`initialBaseUrl/SessionId = null`,屏自己解析)/ 会话详情路由(`agent-session/{baseUrl}/{instanceName}/{sid}`,路由给出);两者共用 `AgentSessionPane`
- **实例目录**(`data/AgentInstances.kt`): 首选 `/api/instances`(supervisor 快照,离线用 `startPort` 拼 baseUrl)/ 回落卡片扇出探 `/api/agent/sessions`(1.5s callTimeout);**不排序**(沿用服务端/卡片自然顺序)
- `pickDefault` 降级链: 记住的且在线 → 第一个在线子实例(跳 `__current__`)/ 第一个在线 / 目录第一条
- 记住最近: `AgentWorkspacePrefs`(DataStore `lan_agent_agent_workspace`),匹配键 **baseUrl**(实例 id 会随重建变);四字段同一 `edit` 事务落盘,分开写会出现半截状态
- 切换实例 in-place(`api = remember(active?.baseUrl)`、`store = remember(currentSid)` 换 key),不 push 路由
- 「选择实例」弹层(`InstancePickerSheet`,照 WorkBuddy「选择设备」): 列表 `heightIn(max = 360.dp).verticalScroll(...)` + `skipPartiallyExpanded = true`;在线用实例栏 running 绿 `#52C41A` + 浅底 `#F6FFED`,离线用文案不用颜色块、不禁用
- 空态三分支: 目录空 → `NoInstanceState`;实例在但无会话 → `NoSessionState`(实例离线时不显示「新建会话」);有会话无消息 → 原来的 `AgentSessionEmptyState`;**没有会话时整条输入区不渲染**

### 18. 会话精简模式

设置栏开关,**默认开**(`UiPrefsRepository.compact_tools`,未设过 = `true`);`compactToolsFlow().collectAsState(initial = true)` 读,改完立刻生效(只影响渲染粒度)。

**分组**(`buildAgentBlocks`,住 `ui/AgentSessionStore.kt`): 一段 = 连续 [工具调用 + 思考];**思考不打断段落**;段内工具数 ≥ 2 才聚合;**正文/用户消息/提示条断开段落**。

**渲染**(`ToolGroupCard`): 折叠态一行 `⚒ 工具调用 · N 次` + 副行名字汇总(`Edit ×4 · Bash ×3`,同校按首次出现合并、>1 时加 `×N`) + 右侧状态(运行中 tertiary 暖橙 / 失败 error 红 + `N 失败` chip / 否则 `完成`) + `⌄`;**点整行**展开(不做下拉手势);展开 key = 段内首条成员 key(流式追加不合并);**块存下标不存快照**(`items[idx]` 读实时值,工具输出原地替换不渲染过期);`remember(items.size, compact)` 缓存是有意的(items 只 append);自动滚动 key 改"块数"(段内增长不动视口)。

### 19. DisplayFiles 文件卡片

- wire: `runtime.tool_call.input.paths` + `runtime.tool_result.output`(JSON 字符串,`content[0].json.files = FileMeta[]`,`kind ∈ text|image|html|binary`)
- **三大坑**: ① transcript 里 `tool_result` 是字面量 `'done'`,元数据只走一次 SSE 且 take-and-delete → **冷启动后只剩路径**(`DisplayFilesCache` 进程内兜"离开再回来") ② `mtime` 浮点(`fs.Stats.mtimeMs`),`size` 别赌整数,一律容错 ③ 路径解析拆两条:`parseDisplayFilePaths(input)`(任何时态,`kind` 客户端按扩展名猜)+ `parseDisplayFileMeta(output)`(仅直播态)+ `mergeDisplayFiles(...)` 合并
- 预览字节: `GET {instanceBaseUrl}/api/fs/preview?path=...` 无鉴权;`maxBytes` clamp `[1024, 1 MiB]`(`PREVIEW_DEFAULT_MAX`,**不是 512KB**),超 413 ETOOBIG;目录 400 EISDIR;不存在 404 → 这三个必须翻译成人话(`previewErrorMessage`)
- 四分支渲染: **图片** → 内联缩略图(`RemoteImageThumb` **必须采样**`inJustDecodeBounds`+`inSampleSize`;SVG 解不了走 WebView);**HTML/SVG** → `FileViewerOverlay` WebView(`WebViewFactory.createForContent`,**textZoom=100**;`shouldOverrideUrlLoading` 一律 true 只读);**文本** → `.md` 自研 Markdown / 其余 `CodeBox`;**binary / 超 1 MiB** → 「在 Mac 上打开目录」(`POST /api/fs/reveal`,`open -R`)
- 限制: ① 单次最多 20 ② 超 1 MiB 不内联 ③ 冷启动只有路径
- 改前看 `data/DisplayFilesTest` + `ui/AgentSessionStoreDisplayFilesTest`(后者守"重开会话",直播正常、只有离开再回来才坏)
- 见 `data/DisplayFiles.kt` / `ui/AgentSessionViews.kt` 的 `DisplayFilesBody` / `ui/FileViewerOverlay.kt`

### 20. 「按住说话」鉴权路径(4 条,`VoiceAsrConfig.providerOrNull` 按序命中)

| # | 条件 | provider | 凭据从哪来 |
|---|------|----------|-----------|
| 1 | `asrSignViaBackend=true` + 有实例 baseUrl | `AsrUrlProvider.Remote` | 实例 `/api/voice/asr-token` 返回**拼好的**握手地址 |
| 2 | `asrUseWorkBuddy=true` + 有实例 baseUrl | `AsrUrlProvider.WorkBuddyApi` | 实例 `/api/voice/getASRToken` 现读桌面端 auth 文件 |
| 3 | `asrUseWorkBuddy=true`(无实例) | `AsrUrlProvider.WorkBuddy` | `local.properties` 里的内置 `asrWbAccessToken` |
| 4 | `asrAppId`+`asrSecretId`+`asrSecretKey` | `AsrUrlProvider.Local` | 端上 HMAC 自签腾讯云 |

- **红线:客户端永不调 WorkBuddy 的 `/v2/plugin/auth/token/refresh`**。refreshToken 一次性轮换,客户端刷一次就把 macOS 桌面端踢下线。要新 token 只能让实例现读桌面端 auth 文件(路径 2)。
- **路径 2 的 401 自愈**:`WorkBuddyApi` 按响应里的 `expiresAt`(epoch ms)缓存 token,到期前 5 分钟重取;`TencentRealtimeAsr.onFailure` 拿到握手响应码 401/403 时 `invalidateAuth()` 清缓存 → 重连一次(仅一次,`authRetried` 守卫)→ 用户无感,`pending` 里的音频不丢。**服务端 `getASRToken` 自己从不返回 401**(读不到文件是 503),所以 401 一定来自 `copilot.tencent.com` 拒签旧 token,重取必得新的。
- 路径 2 的 GET 用 `httpGetBody`(非 2xx 也把 body 交给 `parseResponse`)—— 否则服务端那句「请确认本机 WorkBuddy 桌面端已登录」会被换成干巴巴的 HTTP 503。
- main 源文件: `voice/VoiceAsrConfig.kt`(选路) / `voice/TencentAsrSignature.kt`(4 个 provider) / `voice/TencentRealtimeAsr.kt`(WS + 401 重连) / `voice/WorkBuddyAsrAuth.kt`(路径 3 的本地续期)
- 单测: `voice/WorkBuddyApiTest.kt`(缓存命中/过期/skew/invalidate/无 expiresAt 不缓存) + `voice/AsrUrlProviderTest.kt` + `voice/TencentAsrSignatureTest.kt`

### 21. 底部任务栏(任务清单 + 后台任务)

**问题**:后台子代理(Agent / CliAgent 工具派出去的)跑在主会话之外 —— 主 agent 早就把 `Agent` 工具卡标成「完成」了,子代理还在跑,会话里完全看不到它。

- 两条来源,合并进 `AgentSessionStore` 的 `bgAgentTasks` / `bgBashTasks`(对齐 opencc-web `useBackgroundTasks`): ① SSE `agent_task.changed` / `bash_task.changed`,payload 是 `{sessionId, task}` ② `GET /sessions/:id/state` 的 `agentTasks` / `bashTasks` 冷启动快照
- **agent 侧服务端每次新 SSE 连接会把当前所有任务合成一条重推**(`routes/event.ts:120-135`),所以断线重连不丢;**bash 侧没有**,冷启动只能靠 state 快照 —— 这条是 bash 会不会显示的分水岭
- 字段名两边**不一样**: agent 是 `task.id` + 5 态(`queued|running|completed|failed|cancelled`),bash 是 `task.taskId` + 4 态(`running|completed|failed|killed`)。写成一个名字会静默解不出东西(列表永远空)
- **不接** `resultText` / `stdout` / `stderr` / `eventCount` —— 这几条能到 MB 级,列表行渲染不到,白占内存(`ignoreUnknownKeys` 直接吃掉)
- 终态任务按 `BG_RECENT_TTL_MS`(60s,同 web)过期,`running`/`queued` 永不清。**两处裁剪缺一不可**: store 侧(有事件时)+ 渲染侧(`TaskDockStrip` 的 `now` 参数,会话静下来之后的兜底)—— 后者吃 `AgentSessionScreen` 那个 15s 的 `clockNow`(原 `drawerNow`,抽屉相对时间也用它,所以改了名)
- 渲染: `TaskDockStrip` **一张卡两段**(任务清单 + 后台任务),不再各起一张卡 —— 底部固定区垂直空间最贵,两张卡各一行 header 就是两行纯装饰。header 按「有什么显示什么」拼: 运行态 + `⚡ N 运行中` chip + `任务清单 d/t`;两段都在时展开体里才加段落标题
- 后台行 `BgTaskRow`: 名字 + 描述挤在**同一个 Text**(AnnotatedString 分段),两个 Text 各自 ellipsize 会让描述被整体挤没;状态色 = 跑中 tertiary / 完成 primary / 失败·killed error / 其余灰,耗时只给终态(跑中要实时钟,不划算)
- 调用方判定用 `store.hasDockContent`,运行态提示条也用它反着判 —— 否则任务栏 header 里已经有 status 了,下面再画一条是重复
- 单测: `data/BackgroundTasksTest.kt`(状态文案 / TTL / 耗时 / 兜底链) + `AgentSessionStoreSseTest.kt`(两路 upsert / 按 id 就地替换 / 过期裁剪 / state 冷启动)
- 见 `data/BackgroundTasks.kt` / `ui/AgentSessionStore.kt` / `ui/AgentSessionViews.kt` 的 `TaskDockStrip`

## 常用命令 + 强制开发规则(合并)

```bash
# 编译 + 装到当前 adb 设备
cd /Users/ethan/code/lan-agent
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:installDebug
adb shell am start -n io.github.hotmanxp.lanagent/.MainActivity

# 本地起 serve,手机扫码下载
npx serve -l tcp://0.0.0.0:8765 app/build/outputs/apk/debug/

# 重置 DataStore(回 seed)
adb shell pm clear io.github.hotmanxp.lanagent

# JVM 单测(钉 wire 坑)
./gradlew :app:testDebugUnitTest --tests "*AgentModelsTest*"
```

**强制规则**:

- `JAVA_HOME` 必须显式设(同上面命令);`/usr/libexec/java_home` 在这台机器是 broken
- `dl.google.com` 不可达 → `settings.gradle.kts` 已加 Tencent Maven mirror(普通网络用户可移除)
- Android SDK 在 `/Users/ethan/Library/Android/sdk`,`local.properties` 已 gitignore
- JDK 21 替代 17(AGP 8.6.1 支持),`compileOptions` 仍保持 `VERSION_17` bytecode target
- **不写 release**(`isMinifyEnabled = false`,`proguard-rules.pro` 空);每次手动 bump `versionCode` + `versionName`(`app/build.gradle.kts`),否则手机装上看不出是新版
- 改入口卡片 → APP 内编辑模式 / `data/Cards.kt` 的 `defaultCards`(只影响卸载重装后的首次启动)/ 网络白名单 → `res/xml/network_security_config.xml`(默认 base-config 已全放行 cleartext,多半不用动);详见 `README.md`

## 配套:opencc-web

`pnpm --filter @zn-ai/zai dev -- --lan`(绑 0.0.0.0,9201 / MobileAgent 路由 8101)。移动 Agent 路由 `/m`;实例管理 `/instances` + `/api/instances` + `/api/fs/picker`。**运行时只剩 `repl` 一种形态**(阶段 3 删 `RuntimeCore` / `--runtimeCore` / `PUT /api/agent/settings/runtime-core`),lan-agent 的 `InstanceRuntimeCore` 同步移除。仓库 `/Users/ethan/code/opencc-web/`,详见 `opencc-web/AGENTS.md`。

## 版本 / 发布

**当前**: 0.18.1 (73) — 修 WebView 全屏页(`webview/{url}`,含 Agent 会话「在网页打开」到 `/m?sid=`)把 `loadUrl` 写在 composable 函数体里:键盘弹起时每帧 IME inset 变化都重组,每次都重新加载整页,页面输入框点一下就被刷掉、一个字打不进去。已把客户端装配 + 首次 `loadUrl` 收进 `LaunchedEffect(webView)`(见 pitfalls「WebView / Compose 渲染」)。0.18.0 (72) 底部任务栏合并「任务清单 + 后台任务」:后台 agent 子代理与后台 bash 的状态直接显示在会话底栏(见 §21)。0.17.5 (71) 按住说话胶囊瘦身;0.17.4 (70) WorkBuddy 语音 401 自愈;0.17.2 模型选择器 provider 显示名;0.17.0 (67) `/` 命令面板 + Skill 候选;0.16.1 (56) 文件预览 overlay;0.16.0 (55) DisplayFiles;0.15.2 (54) 会话精简模式;0.15.0 (52) 任务栏 = 原生 Agent 工作区;0.14.0 (49) 底部五栏。中间 patch bump(0.14.1 / 0.14.2 / 0.16.2-0.16.5 等)见 git log;**不发 release**;0.17.1 与 0.17.3 是未发版的占位号。
