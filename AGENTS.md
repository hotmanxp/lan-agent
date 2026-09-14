# AGENTS.md — `/Users/ethan/code/lan-agent/`

> **lan-agent** — 简单 Android App,把局域网内多个 opencc-web 实例的入口收成卡片列表,点击卡片进入 WebView 详情加载对应 URL;同时**原生**展示 zai 实例管理 API(启动/停止/重启/删除/打开/二维码扫码添加);**原生 Agent 会话列表 + 会话详情**(直连 `/api/agent/sessions` + `/api/event` SSE,不走 WebView);**SSH 一键启动 zai**(当 Mac 没起来 zai 时)。配套工程是 `/Users/ethan/code/opencc-web`,zai 需用 `pnpm --filter @zn-ai/zai dev -- --lan` 启动才能让手机访问(SSH 模块则全局 `zai --lan --port <p>` 启动)。
>
> **0.10.0 起视觉体系整体对齐 WorkBuddy**:浅灰页底 + 白色卡片 + 官方机器人形象,并给助手正文接了**自研 Markdown 渲染**(见 §15)。
> **0.10.1** 收尾两处「还是不像」的地方:**用户气泡改中性浅灰**(不是品牌绿)+ **输入条改 WorkBuddy 双行白卡**(上排文本域 / 下排工具条,发送钮常驻,删掉卡下方的 icon row)。
> **0.10.2** 把亮色主题的**品牌色改回平安橙 `#ff6600`**(0.10.0 临时改成 WorkBuddy 青绿 `#0CC8A6`,现改回 zai `/m` 的 AI-Agent 头像家族色)。深色主题保持原青绿 `#35D6B6`。
> **0.10.3** 收尾运行态徽标:**左对齐** + **三个小点波浪动画** 替代文字(0.10.2 还顶着「运行中/重试中」文字 + 主题色,稍重),颜色统一走 `onSurfaceVariant` 灰,只做轻提示不抢输入框。
>
> **未发版(versionCode 仍 44 / 0.10.6)** 输入条接上 **「按住说话」语音输入**,走**腾讯云实时语音识别(WebSocket)**(新包 `voice/`,见 §16)。**不是替换**系统 SpeechRecognizer,而是**优先**:`voice/VoiceAsrConfig.providerOrNull(baseUrl)` 返回 null(没配密钥)时输入条行为跟改动前完全一致。凭据从 `local.properties`(已 gitignore)读,经 `BuildConfig.ASR_*` 注入;**SecretKey 不硬编码**。要出局域网需切后端签发(实例侧 `/api/voice/asr-token`)。
>
> **当前 HEAD**: HEAD on `main` · **versionCode 44** · **versionName 0.10.6**

## 仓库用途

`/Users/ethan/code/lan-agent/` 是一个**独立顶级目录、独立 git 仓库**,**不在 opencc-web monorepo** 内。spec / plan 文档在 `docs/superpowers/{specs,plans}/`。

## 目标 / 非目标

### 目标

- 单 Activity + Jetpack Compose + Navigation Compose
- 首屏卡片列表(数据来源: 写死的 `defaultCards` + DataStore 运行时增删改)
- **原生实例管理屏**(`InstancesScreen` + `InstanceCard`):直连 `/api/instances` 拉快照,2.5s 轮询
- **原生 Agent 会话列表 + 会话详情**(0.9.0, 0.9.1 修 wire 兼容):从实例卡「会话」按钮进 → 会话列表 → 会话详情,直连该实例的 `/api/agent/sessions` / `/api/agent/sessions/:id` / `/api/event?sid=` SSE,支持发消息 / 中断 / 队列 steer / 权限确认 / 问询 / 文档审核,不走 WebView
- **WorkBuddy 视觉体系**(0.10.0, 0.10.1 精修, 0.10.2 改品牌色):固定色板(浅灰页底 + 白卡 + 品牌平安橙,动态取色关闭)、官方机器人形象、启动图标、字号阶梯,全 App 一致;**用户消息气泡中性浅灰 + 四角同半径**(0.10.1);**输入条 = 双行白卡**(上排文本域 / 下排 `语音 · 模型 chip · + · 发送钮`,0.10.1);助手正文 / 思考过程 / 工具输出走 **Markdown 渲染**(`ui/Markdown.kt`)
- **三种添加实例**:手动表单 / 目录选择器 / **QR 扫码**(CameraX + ML Kit)
- **SSH 一键启动 zai**(`SshHostListScreen` + JSch):在 Mac 没起来 zai 时,通过 SSH 远程执行 `nohup zai --lan --port <zaiPort>` 一键拉起,自动探测端口 + 跳 InstancesScreen WebView
- **WebView 长连接保活**:dataSync foreground service + detached WebView,Activity onPause 后 SSE / WebSocket / long-poll 仍跑
- WebView **文件上传**支持:系统选择器 → `window.lanAgentAttachImages` bridge 注入 base64
- **可拖拽浮刷新按钮**:位置持久化到独立 DataStore
- APK 侧载,debug only

### 非目标(明确不做)

- 不做账号 / 鉴权(opencc-web 配套侧也不需要)
- **不发 release**(只 debug APK)
- **不写自动化测试**(手动验收为主)
- 不引入 ViewModel / Room / Hilt(用 Compose state + DataStore 已够)
- 不做 release 签名 / ProGuard
- 不做 iOS / 鸿蒙

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| 语言 | Kotlin | 2.0.21 |
| 构建 | Gradle / AGP | 8.10 / 8.6.1 |
| UI | Jetpack Compose (BOM) | 2024.10.00 |
| 导航 | Navigation Compose | 2.8.4 |
| 持久化 | DataStore Preferences | 1.1.1 |
| 序列化 | kotlinx-serialization-json | 1.7.3 |
| Markdown | **自研**(`ui/Markdown.kt`,不引第三方库) | — |
| WebView | AndroidX Webkit | 1.12.1 |
| 相机 | CameraX (core/camera2/lifecycle/view) | 1.3.4 |
| 扫码 | ML Kit Barcode Scanning | 17.3.0 |
| HTTP | OkHttp | 4.12.0 |
| minSdk / target / compile | 26 / 34 / 34 | — |
| JVM target | 17 | — |

Kotlin DSL,version catalog `gradle/libs.versions.toml`。包名 `io.github.hotmanxp.lanagent`。

## 目录结构

```
lan-agent/
├── build.gradle.kts                  # 根(只声明 plugins)
├── settings.gradle.kts                # 含 :app + Tencent Maven mirror
├── gradle.properties                  # AndroidX + Kotlin DSL 开关
├── gradle/libs.versions.toml          # version catalog
├── gradle/wrapper/                    # Gradle 8.10
├── gradlew / gradlew.bat
├── .gitignore
├── README.md                          # 用户向文档(验收清单)
├── AGENTS.md                          # ← 本文件
├── docs/superpowers/
│   ├── specs/2026-08-24-lan-agent-android-app-design.md
│   ├── specs/2026-09-14-workbuddy-api-token-applicability.md
│   └── plans/2026-08-24-lan-agent-android-app.md
└── app/
    ├── build.gradle.kts              # compileSdk 34 / minSdk 26
    ├── proguard-rules.pro             # 空(debug-only)
    └── src/main/
        ├── AndroidManifest.xml        # 单 Activity + 6 类权限 + service
        ├── res/
        │   ├── values/{strings,themes,colors}.xml
        │   ├── values-night/{themes,colors}.xml          # 深色冷启动底色 #141517
        │   ├── xml/network_security_config.xml   # base-config cleartextTrafficPermitted="true"
        │   ├── mipmap-anydpi-v26/                 # 自适应图标(青绿底 + 机器人头前景)
        │   ├── mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/  # 各密度 PNG(青绿底 + 机器人头)
        │   └── drawable-nodpi/
        │       ├── wb_mascot.png                 # WorkBuddy 机器人形象(空态用)
        │       └── ic_launcher_foreground.png    # 自适应前景(108dp 画布,66dp 安全区)
        └── java/io/github/hotmanxp/lanagent/
            ├── MainActivity.kt        # setContent + immersive + 媒体权限申请
            ├── LanAgentApp.kt         # Application;注册 WebViewKeepAlive 通知 channel
            ├── ui/
            │   ├── LanAgentTheme.kt   # WorkBuddy 色板 + 字号阶梯 + 状态栏明暗(无动态取色)
            │   ├── Markdown.kt        # 自研 Markdown:块级解析(纯函数)+ Compose 渲染 + CodeBox
            │   ├── AppNavHost.kt      # NavHost: home / scan / instances/{baseUrl} / agent-sessions/{baseUrl}/{instanceName} / agent-session/{baseUrl}/{sid} / ssh-hosts / webview/{url}
            │   ├── HomeScreen.kt      # 首页卡片列表 + 4 按钮(scan/instances/edit/add)+ 编辑模式(增删改拖拽)
            │   ├── EditCardDialog.kt  # 旧卡片增改对话框(HomeScreen 用)
            │   ├── WebViewScreen.kt   # 全屏 WebView + 文件上传 + Service 启停 + 已刷新 snackbar
            │   ├── InstancesScreen.kt # 原生实例管理(2.5s 轮询 + 6 动作 + 3 弹窗)
            │   ├── InstanceCard.kt    # 单张实例卡(状态 Tag + LAN Switch + 描述列表 + 动作行,动作行可横滚)
            │   ├── InstanceFormat.kt  # 运行时长 / 相对时间(ISO + epoch ms 两版)/ 时间戳格式化 helper
            │   ├── CreateInstanceDialog.kt  # 创建实例:名称/cwd/LAN/端口/类型
            │   ├── EditPortDialog.kt  # 编辑启动端口
            │   ├── DirectoryPickerDialog.kt  # 文件系统目录选择器(拉 /api/fs/picker)
            │   ├── AgentSessionsScreen.kt  # 原生会话列表(5s 轮询 + 新建会话)
            │   ├── AgentSessionScreen.kt   # 原生会话详情(hydrate transcript + SSE reduce + 发消息/中断/队列/权限/审核)
            │   ├── AgentSessionStore.kt    # 会话状态机:transcript 归一化 + SSE 事件 reduce → AgentItem 列表
            │   ├── AgentSessionViews.kt    # 消息渲染组件(用户气泡/助手正文/思考折叠/工具卡/ask·permission·approve 卡/双行白卡输入条)
            │   └── VoiceInput.kt           # 语音转文字(平台 SpeechRecognizer + 权限申请 + 部分结果回填)
            ├── data/
            │   ├── Cards.kt           # 5 张 hardcode 默认卡片 + findManagerBaseUrl
            │   ├── CardRepository.kt  # DataStore 持久化(`lan_agent_cards`)+ resetCards()
            │   ├── UiPrefsRepository.kt  # 浮按钮拖拽位置持久化(`lan_agent_ui_prefs`)
            │   ├── SshRepository.kt   # SSH host DataStore(`lan_agent_ssh_hosts`)
            │   ├── InstanceModels.kt  # InstanceSnapshot/State/AppProfile + FsPickerEntry
            │   ├── InstancesApi.kt    # OkHttp 客户端 + PatchValue 三态
            │   ├── AgentModels.kt     # 会话/transcript/SSE 事件 wire 模型 + JsonElement 取值 helper + 容错时间戳序列化器
            │   ├── AgentApi.kt        # 会话 HTTP 客户端 + callbackFlow 版 SSE(重连/退避/seq 去重)+ prompt 请求体构造
            │   └── ImageAttachment.kt # 图片附件:选图 → 采样解码 → 白底铺平 → JPEG 重编码 → base64
            ├── model/
            │   ├── Card.kt            # @Serializable data class(accent 存 ARGB Int)
            │   └── SshHost.kt         # @Serializable data class(id/name/host/port/user/password/zaiPort)
            ├── ssh/
            │   ├── JschClient.kt      # JSch 0.1.55 封装(单次 Session,exec 后 disconnect)
            │   ├── ZaiLauncher.kt     # 全局 `zai --lan --port ${zaiPort}` 命令预设 + start/stop/tailLog
            │   └── ZaiPortProbe.kt    # OkHttp 1s × 5 次轮询 9201
            └── service/
                ├── WebViewFactory.kt  # WebView 配置单源(避免 foreground/background settings 漂移)
                └── WebViewKeepAliveService.kt  # dataSync foreground service,挂 detached WebView
```

## 关键设计决策

### 1. 入口数据 = 写死 seed + DataStore 持久化

- `data/Cards.kt` 里 hardcode 5 张默认卡片,首张 `seed-instances` 指向 `http://$HOST:9201/instances`(其余 4 张是 `9988` / `9977` / `9966` / `9955` 四个 zai 实例的入口页)
- 首次启动 `Context.cardsFlow()` 读 DataStore;无 key → 返回 `defaultCards`
- 用户用 APP 内编辑模式增/删/改/拖拽 → 写入 DataStore
- 改 `Cards.kt` 不会影响已装用户的现存数据(只有卸载重装才回到 seed)

### 2. 首页 = 5 入口按钮 + Card 列表

HomeScreen 顶栏右侧 5 个 IconButton(顺序固定):

| 顺序 | 按钮 | 行为 |
|------|------|------|
| 1 | **QR 扫码**(图标 `QrCodeScanner`) | 进 `scan` 路由 → `ScanQrScreen` 扫 zai 分享二维码 → 解码 URL → 直接跳 `webview/{url}` |
| 2 | **实例管理**(图标 `Storage`) | 从 Card 列表里识别指向 `/instances` 的卡片,提取 `host:port` 作为 API base → 进 `instances/{baseUrl}` 路由 → `InstancesScreen` |
| 3 | **编辑模式**(图标 `Edit`) | 进入拖拽编辑态,长按拖拽换位,显示 🗑 + ☰ |
| 4 | **添加卡片**(图标 `Add`) | 弹 `EditCardDialog` 输入新 Card |
| 5 | **SSH 主机**(图标 `Memory`) | 进 `ssh-hosts` 路由 → `SshHostListScreen`(管理多台电脑的 SSH 凭证 + 一键启动 zai) |

`findManagerBaseUrl(cards)`(`data/Cards.kt`)是**唯一**识别"实例管理入口"的方法 — URL 路径以 `/instances` 结尾,提取 `http://host:port` 部分;找不到时 HomeScreen 弹"未配置实例管理入口卡片"对话框,引导用户先去首页 + 加卡。

### 3. 原生实例管理屏(InstancesScreen)

- 入口:HomeScreen → Storage 按钮 → `instances/{baseUrl}` 路由
- 数据源:`baseUrl/api/instances`(`InstancesApi.listInstances()`),**`repeatOnLifecycle(STARTED)` 包裹的 2.5s 轮询**,STOPPED 自动停
- 单卡片 `InstanceCard` 视觉对标 web `Instances.tsx`:name + 当前 Tag + 状态 Tag(stopped/starting/running/stopping/down 五态配色对齐 web)+ LAN Switch + 启动端口(可编辑)+ 运行端口/cwd/PID/启动时间/运行时长/创建时间/最后心跳/错误 + 5 个动作按钮(启动/停止/重启/删除/打开)
- **`down` 超过 3 分钟视作 `stopped`**(`STALE_THRESHOLD_MS`),让"启动"按钮可点(对齐 web 端 effectiveState)
- 30s 一次的 `now` tick — 让运行时长 / 相对时间不卡在同一数字
- 操作防抖:`lanBusy` / `actionBusy` 两个 `mutableStateListOf<String>` 记 instanceId,按钮转圈
- 三种创建方式:
  - **手动表单**(`CreateInstanceDialog`):name / cwd(可点"浏览"拉 DirectoryPicker)/ LAN checkbox / 端口(自动/手动)/ **实例类型(标准 / task-factory)**
  - **目录选择器**(`DirectoryPickerDialog`):拉 `/api/fs/picker?path=...`,展示父子导航 + 主页/上级按钮
  - **QR 扫码**:`ScanQrScreen` 扫 zai 分享的 URL 直接 `webview/{url}`,**不进** InstancesScreen(扫码是给快速进 zai 用的,不是添加实例)

### 4. 网络: 全局放行 cleartext

`AndroidManifest.xml` 设 `android:usesCleartextTraffic="true"`,`network_security_config.xml` 的 `base-config cleartextTrafficPermitted="true"`。

这是 LAN 工具的合理取舍 — 之前试过白名单 `192.168.0.0` + `includeSubdomains="true"`,但 Android 对 IP + `includeSubdomains` 是 **exact match**,不扩展到整个 /16 段,白名单机制玩不转。

### 5. WebView 配置

```kotlin
settings.javaScriptEnabled = true
settings.domStorageEnabled = true
settings.useWideViewPort = true      // 接受 <meta viewport width=device-width>
settings.loadWithOverviewMode = false // 不强制 fit content
settings.textZoom = 85              // opencc-web /m 没 responsive typography,缩 15%
setBackgroundColor(android.graphics.Color.TRANSPARENT)
```

`onReceivedError` 是 **no-op**(LAN 工具 ERR_FAILED 太频繁,Snackbar 噪声)。

### 6. WebView 文件上传(`onShowFileChooser`)

zai 上传图片 → `<input type="file" accept="image/*">` → WebChromeClient.onShowFileChooser:

1. **手动构建 pickIntent**(不依赖 `params.createIntent()`)— 部分 OEM ROM(MIUI / ColorOS / 老 WebView)的 createIntent 不带 `FLAG_GRANT_READ_URI_PERMISSION`,系统选择器会静默失败 / 返回 RESULT_CANCELED / 空 data
2. `Intent.createChooser(...)` 强制弹出选择 UI(即便只有一个候选)
3. 拿回 `content://` URI 后:
   - **优先**用 `window.lanAgentAttachImages` bridge(`scope.launch` + `ContentResolver.openInputStream` 读 bytes → base64 → `evaluateJavascript` 注入) — 绕过 WebView 在 content:// 上的脏转换
   - **fallback**:bridge 不存在或读失败 → 让 WebView 走标准路径,接受 OEM ROM 上的不可靠

### 7. SSH 启动 zai(0.7.x)

加一个独立模块让手机在 zai 没启动时一键拉起来。

**模块**:
- `model/SshHost.kt` — `@Serializable data class SshHost(id, name, host, port=22, user, password, zaiPort=9201)`
- `data/SshRepository.kt` — 独立 DataStore `lan_agent_ssh_hosts`,key=`ssh_hosts_json`,完全照搬 CardRepository 模式
- `ssh/JschClient.kt` — JSch 0.1.55 封装:connect + exec,统一抛 `SshException`,Session/Channel 显式 disconnect 防 FD 泄漏;`StrictHostKeyChecking=no`(LAN 工具无 MITM 威胁模型)
- `ssh/ZaiLauncher.kt` — 命令预设 + suspend `start/stop/tailLog`(withContext(IO))
- `ssh/ZaiPortProbe.kt` — OkHttp 1s × 5 次轮询 `${zaiPort}/instances`,成功 200 后自动跳 WebView
- `ui/SshHostListScreen.kt` + `ui/EditSshHostDialog.kt` — 列表 + 启停半屏 sheet

**命令模板**(写在 `ZaiLauncher.kt`):
- **PATH 兜底**:前置 `export PATH="$HOME/.local/bin:$HOME/.bun/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"; source ~/.zshenv 2>/dev/null; source ~/.bashrc 2>/dev/null;` — JSch 的 `exec` 跑在 non-interactive non-login shell,`.zshrc` 不被 source,sshd 默认 PATH 缺 Homebrew;同时把 `~/.local/bin`(npm-global)、`~/.bun/bin`、`/opt/homebrew/bin`、`/usr/local/bin` 显式 prepend 兜底
- **start**:`nohup zai --lan --port ${host.zaiPort} > /tmp/zai.log 2>&1 & disown` — 用**全局 `zai` 二进制**(不再走 `pnpm --filter @zn-ai/zai dev`),所以不依赖 cwd 是 `~/code/opencc-web`
- **stop**:`pkill -f 'zai.*--lan' && echo stopped || echo nothing_to_stop`
- **tail log**:`tail -50 /tmp/zai.log 2>&1`
- `nohup ... & disown` 让 zai 脱离 SSH shell,SSH session 关闭后继续跑;`exec` 调用 ~50ms 返回(不等 zai 监听端口,后续用 `ZaiPortProbe` 轮询)

**进程方案选择 nohup+disown**(与用户对齐):不用 tmux(用户需装)、不用 LaunchAgent(配置复杂)。LAN 工具场景够了。

**`zaiPort` 字段**:每条 SSH host 独立配置 `zaiPort`,默认 9201(zai 默认端口)。当 9201 被另一个 supervisor 占了时可换端口,避免 `EADDRINUSE`(`zai --lan` 不会自动扫描空闲端口)。

**已知坑**:

| 现象 | 排查 |
|------|------|
| `connect failed` | Mac 「系统设置 → 通用 → 共享 → 远程登录」 没开(macOS 13+),或 IP 错 |
| `Auth fail` | 密码错,或 Mac 用户没勾「允许远程登录」 |
| exit 127(`command not found`) | sshd PATH 不全,`~/.zshenv` 加 `export PATH="/opt/homebrew/bin:$PATH"`(或全局装 npm/zai) |
| exit 0 但端口不通 | 全局 `zai` 没装;`which zai` 在 Mac 上验证;或 `zaiPort` 已被占 |
| 端口探测超时 | zai 启动慢(冷启动 5-10s),5s 内探测失败正常;等几秒手动点 Storage 进 InstancesScreen 看 |

**改路径 / 改命令**:`ssh/ZaiLauncher.kt` 的 `PATH_PREFIX` / `ZAI_PORT`(改完重 build 只影响卸载重装后的首次启动,已有 SSH host 数据从 DataStore 读)。

**改依赖**:JSch 在 `gradle/libs.versions.toml` `[versions] jsch = "0.1.55"` + `[libraries] jsch`,`app/build.gradle.kts` `implementation(libs.jsch)`。Tencent mirror 已代理。

**密码明文**:与 Card.url 一致,DataStore 存明文。Phase 2 接受,Phase 3 再上 Keystore 加密。

`MainActivity.onCreate` 会主动申请 `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (更早),但只影响 base64 注入是否成功,不会阻塞选图弹窗。

### 8. WebView 后台保活(WebViewKeepAliveService)

Android `Activity.onPause` 会冻结 WebView 网络栈 — 用户切走再回来时 SSE / WebSocket / long-poll 全断。

解法:`dataSync` foreground service:

- 持一个**未附加到 View hierarchy 的 detached WebView** — Chromium 检测到没 surface 就跳过 rasterization,但 JS engine + 网络栈照跑(正是想要的)
- `WebViewScreen` 的 `DisposableEffect(url)` 启停服务;切走 WebViewScreen(onDispose)就 stop,回到 HomeScreen 后 stop 触发,服务自销毁
- 30 分钟 `PARTIAL_WAKE_LOCK` 超时是兜底(防止 caller 崩了忘 stop 把电池榨干)
- API 34 用 3-arg `startForeground(NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`,匹配 manifest 的 `foregroundServiceType="dataSync"`
- 通知 channel ID `webview_keepalive`(`LanAgentApp.onCreate` 注册,IMPORTANCE_LOW,silent)— 部分 OEM ROM(MIUI)对 MIN 通道隐藏,所以选 LOW
- 通知 ongoing 用户不能滑掉,点回 tapIntent(`FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`)回 MainActivity
- `START_STICKY`:被 OS 杀掉的进程恢复时用空 intent 启动,fallback `about:blank`(MIUI / EMUI / ColorOS 不理 STICKY,已知 limitation — 让用户在系统设置里加白名单)

### 9. WebView 背景色陷阱

`webView.setBackgroundColor(...)` 在 hardware-accelerated surface 下是 no-op。深色背景必须**在 Compose 层画**:

```kotlin
Box(Modifier.fillMaxSize().background(Color(0xFF1F2937))) {
    AndroidView(factory = { webView.also { it.setBackgroundColor(TRANSPARENT) } }, ...)
}
```

### 10. 系统栏(immersive)

`MainActivity.onCreate`:

- `WindowCompat.setDecorFitsSystemWindows(window, false)` — edge-to-edge
- `WindowInsetsControllerCompat.hide(WindowInsetsCompat.Type.navigationBars())` — 隐藏**底部导航栏**
- `WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` — 边缘 swipe 临时显示
- **状态栏保留可见**(用户要看到时间/电池)
- 状态栏背景设 `Color.TRANSPARENT`
- **状态栏图标颜色跟着主题切**(0.10.0):`LanAgentTheme` 里
  `WindowInsetsControllerCompat.isAppearanceLightStatusBars = !darkTheme` —— 内容画到状态栏
  后面,浅色页面必须配深色图标,否则状态栏等于隐形。`values/themes.xml` /
  `values-night/themes.xml` 只负责冷启动那一帧的窗口底色(`#F8F8F8` / `#141517`),
  别把配色逻辑写进 XML。

`WebViewScreen` AndroidView 加 `windowInsetsPadding(WindowInsets.statusBars)` + `padding(top = 4.dp)` + `imePadding()`(让出状态栏 + 软键盘,避免输入框被 IME 盖住)。**不要用负 padding**(`(-8).dp`),某些 Compose 版本会抛 IllegalArgumentException。

### 11. WebView 顶栏 / 返回 + 浮按钮位置持久化

**无 App 顶栏**;WebView 右中浮一个 28dp `Box + clickable` 圆形刷新按钮(不用 `IconButton`,因为它的 `minimumInteractiveComponentSize = 48dp` 会覆盖 `Modifier.size` 让圆圈固定 48dp)。刷新后 `webViewClient.onPageStarted` 弹 "已刷新" snackbar 确认。

**浮按钮可拖拽**:长按拖动改变位置;落点写入 `data/UiPrefsRepository.kt`(独立 DataStore `lan_agent_ui_prefs`,key=`refresh_btn_x/y`)。下次启动从 DataStore 读取恢复。**不要把位置写到 CardRepository** — 卡片 schema 演进时不会拖累 UI 偏好。

返回用 Android 系统手势 / 返回键(`BackHandler` 接管 — `webView.canGoBack()` 时 `webView.goBack()`,否则 `popBackStack()` 回 HomeScreen)。

### 12. WebView 配置单源(`service/WebViewFactory.kt`)

可见 `WebViewScreen` 和后台 `WebViewKeepAliveService` 都用 WebView;若 settings 在两边各自写一遍,foreground 进入后台时 settings 漂移会让 session 形状变(SSE 重连定时器、cookie jar、text zoom 等悄悄重置)。

解法:`service/WebViewFactory.kt` 的 `WebViewFactory.create(context, url)` 是**唯一**创建入口,集中:

```kotlin
settings.javaScriptEnabled = true
settings.domStorageEnabled = true
settings.useWideViewPort = true      // 接受 <meta viewport width=device-width>
settings.loadWithOverviewMode = false // 不强制 fit content
settings.textZoom = 85              // opencc-web /m 没 responsive typography,缩 15%
setBackgroundColor(android.graphics.Color.TRANSPARENT)
```

`WebViewScreen` 和 `WebViewKeepAliveService` 都调 `WebViewFactory.create(...)`,settings 改动只需要改一处。

### 13. 实例启动 profile `app`(0.8.0,0.8.1 加 weixin,对齐 opencc-web)

`data/InstanceModels.kt` 的 `InstanceAppProfile { TaskFactory, Weixin }`(枚举名,`@SerialName` 映射到字符串字面量 `'task-factory'` / `'weixin'`)对齐 opencc-web `packages/zai/src/shared/instances.ts` 的 `InstanceDefinition.app?: 'task-factory' | 'weixin'`。

- **标准实例** = `app` 字段缺省(`null`)。服务端不写 `app` 字段,行为与既有实例一致。
- **任务工厂实例** = `app = 'task-factory'`。supervisor spawn 时把它转成 `--app task-factory` flag 传给子进程,`cli/index.ts` 把 `process.env.ZAI_APP = 'task-factory'` 落到进程环境,`routes/agent.ts` 强制把 `mainAgent` 锁定为 `'task-factory'`(不走全局 `settings.mainAgent`),`/api/system` 回显后前端 `TaskFactoryRedirect` 把入口重定向到 `/super-tasks`。
- **微信专用实例**(0.8.1 新增) = `app = 'weixin'`。机器上**唯一**持有微信通道 owner 锁、负责收发微信消息的进程,由主实例按 `settings.weixinBot` 自动拉起(`packages/zai/src/server/services/weixinBot/weixinDedicatedInstance.ts`),一般不通过 UI 创建。卡片头部展示绿色 `weixin` tag(`WeixinTag`,#07C160,区分于运行态 #52C41A 与任务工厂橙 #D48806)。
- **`null` 与未知字符串都 400**(`packages/zai/src/server/routes/instances.ts:165-171` 的 `parseAppField`),所以 `InstancesApi.createInstance` 在 `app != null` 时才把 key 写进 body,避免发字面 `null`。用 `when` 显式映射枚举 → 字符串字面量(Kotlin 编译器在新增枚举值时给出 non-exhaustive 警告),避免硬编码字符串漏改。
- **创建后不可改** — PATCH `/api/instances/:id` 不接受 `app` 字段(`InstanceSnapshot.app` 只读显示)。
- **UI**:InstancesScreen 顶栏 actions 加 `RocketLaunch` 快捷按钮(对齐 web 端 `RocketOutlined` + `data-testid="new-task-factory-instance"`),打开 Modal 时预选 `app='task-factory'` 并预填 `currentCwd`;右下 FAB 「新建实例」保持标准实例入口。Modal 内「实例类型」 Radio.Group(`标准实例` / `task-factory`)对齐 web 端 `app-radio` — **`weixin` 不在创建表单里露**(对齐 web UX,服务端自动管),只通过卡片 tag 展示。

### 14. 原生 Agent 会话（0.9.0 → 0.9.2）

把「看 Agent 在干什么」从 WebView 收回原生。以前只能点「打开」把 `/m` 塞进 WebView；现在实例卡多一个「会话」按钮，进原生会话列表 → 原生会话详情。

**入口链路**：`InstancesScreen` 实例卡「会话」按钮（`Icons.AutoMirrored.Filled.Chat`，只要有 `inst.port != null` 就能点）→ `agent-sessions/{baseUrl}/{instanceName}` → 点某条 → `agent-session/{baseUrl}/{sid}`。

`baseUrl` = `http://<host from 实例管理 baseUrl>:<inst.port>`。**每个实例都是完整 zai 进程**，自带 `/api/agent/*` 与 `/api/event`，所以不需要经由 `/api/instances` 转发。route 里 baseUrl 含 `://` 和 `:`、instanceName 可能是中文，两处都必须 `Uri.encode`（`Uri.encode` 会编码 `/` 和 `:`，正好保证不碎在 path 分隔符上）。

**数据流三步（顺序不能换）**：
1. `GET /api/agent/sessions/:id` → `{transcript:{meta,messages}}` → `AgentSessionStore.hydrate()` 归一化成 `AgentItem` 列表
2. `GET /api/agent/sessions/:id/state` → `{cwd,v2Tasks,bashTasks,agentTasks}` → `hydrateState()`（可选增强，失败静默）
3. `GET /api/event?sid=<sid>` SSE → `AgentSessionStore.apply()` 增量 reduce

**为什么必须先 hydrate 再连 SSE**：服务端首次连接（不带 `Last-Event-ID`）时，会把 `runtime.delta` / `thinking` / `tool_call` / `tool_result` 从 replay 里过滤掉（`eventBus.ts:100-108` `STREAMING_REPLAY_EXCLUDE`），因为这些内容已落盘进 transcript、重放会重复。反过来说这些内容**只能**靠 hydrate 拿到。

**transcript 不是归一化事件流**，是磁盘 JSONL 原文，解析要过四道关：
1. `message` 可能整条缺失（`session-meta` / `custom-title` / `queue-operation` / `file-history-snapshot` 控制行）→ 先 `entry.message?.content ?: continue`
2. `type` 白名单 `{user, assistant, tool_use, tool_result}`，其余（`system` / `attachment` / `compact_boundary` / 各种控制行）跳过
3. `isMeta == true` 必须隐藏（给 LLM 看的旁路内容，如展开后的 slash 指令、inbox 注入）
4. `message.content` 既可能是 `String`（user 纯文本）也可能是 `ContentBlock[]`；**tool_result 藏在 `type:"user"` 的条目里**，必须贴回对应的工具卡而不是渲染成用户气泡

**`timestamp` 是双形态（实测坑）**：同一个文件里，`assistant` / `user` 条目是 epoch 毫秒**数字**，而 `system` / `queue-operation` 是 ISO-8601**字符串**（`"2026-09-06T04:06:35.048Z"`）。所以 `TranscriptEntry.timestamp` 声明成 `JsonElement?` + `tsMs` 访问器折算，**不能声明 `Long`** —— 否则一条 system 条目就能让整份 transcript 反序列化失败，表现是整个详情页打不开（`isLenient` 救不了 quoted-string→number）。

**服务端时间戳一律当「可能是浮点」处理（0.9.1 真机踩到）**：`GET /api/agent/sessions` 的 `updatedAt` 直接来自 Node `fs.Stats.mtimeMs`，是**带小数的 double**（`1789274126878.9248`）。实测 4 个在跑实例 **115/115 条全会话的 `updatedAt` 都是 float**，不是偶发。把它声明成 `Long` 会让 `decodeFromString` 抛 `Unexpected symbol ':' in numeric literal at path: $.sessions[0].updatedAt` → **整个会话列表页报错打不开**。`createdAt` 走 `Date.now()` 是整数，但别赌。

> **通用规则**：zai 没有 OpenAPI / JSON Schema，wire 类型全是实测倒推的。**任何展示型/统计型的数值字段都可能出现 Python float 那类形态**（0.5 这种整数浮点也一样危险 —— Kotlin `Long` 解码器看到 `2.0` 也会炸）。时间戳字段一律挂 `@Serializable(with = EpochMsSerializer::class)`（`EpochMsSerializer` / `EpochMsNullableSerializer`，容忍整数 / 浮点 / 数字字符串 / ISO 字符串，取整误差 < 1ms 对「x 分钟前」无影响）；从 `JsonObject` 随手取值也走 `toEpochMs()` 而不是 `toLongOrNull()`。

**这个模块有 JVM 单测了（0.9.1 起）**：`app/src/test/java/.../data/AgentModelsTest.kt` 把上述实测坑逐条钉成用例（浮点 mtime、双形态 timestamp、stat 失败回 0、未知字段容忍、tool_result 三种形态）。跑法：

```bash
./gradlew :app:testDebugUnitTest          # 全量
./gradlew :app:testDebugUnitTest --tests "*AgentModelsTest*"
```

**每发现一个新的 wire 形态坑，就往这个文件加一条用例** —— 这类 bug 的特点是「只在新数据上炸」，靠手点很难复现，靠断言才拦得住。

**SSE 用 `callbackFlow` 手搓**（`AgentApi.eventStream`），不引 `okhttp-sse`：

- OkHttp 的独立 `sseClient` **`readTimeout` 必须为 0** —— 服务端心跳 15s 一次，任何有界超时都会在空闲时掐掉长连接
- **不传 `topics`**：`ServerEventBus.topicMatches` 的 topic 白名单（`eventBus.ts:223-245`）里**没有** `prompt.approve` / `prompt.permission` / `queue.changed`，一旦传 topics 这些事件永远收不到。只传 `?sid=` 才拿到完整事件面
- **服务端补发按 `eventId` 匹配，但 `id:` 行写的是 `seq` 数字**（`sse.ts:45-54` vs `eventBus.ts:208`）→ 回传的 `Last-Event-ID` 永远 miss → 每次重连都退化成「全量 replay 最近 256 条」。所以**去重必须在客户端按 seq 单调丢弃**（`seq <= lastSeq` 丢）。这样重连窗口内漏掉的事件 seq 更大会被保留，已应用的 seq 更小会被丢掉
- **取消语义要靠标志位，不能只靠 `call.cancel()`**：`Call.cancel()` 不是线程中断，阻塞读抛的异常会被 catch 吞掉，循环会继续**重新建连** → collect 早结束了后台线程却永远重连（漏 socket + 白耗电）。`awaitClose` 里既 `cancel()` 又置 `cancelled` 标志，循环条件 / catch 分支 / 退避睡眠三处都看这个标志（退避按 250ms 分片轮询标志，避免退出时白等 15s）

**用户消息必须本地乐观追加**（`AgentSessionStore.appendLocalUser`）：SSE 事件面里**没有**「用户发了消息」这一类（`runtime.*` 全是助手侧），用户消息只在 assistant 回复落盘时间接进 transcript。不本地追加的话，自己刚发的消息要等下一次 re-hydrate 才出现。

**工具卡 key 固定 `tool-<toolUseId>`**：同一次调用可能在 `assistant` 消息块和独立的 `tool_use` 行里各出现一次，靠 key upsert 去重（web 端同款）。另外**工具卡一出现就清掉流式气泡游标**（`curTextIdx` / `curThinkIdx`）—— 否则工具之后的 text 会 append 到工具卡**前面**那个旧气泡里，视觉顺序就错了。

**渲染细节**：
- 消息列表用 `reverseLayout = true`（index 0 贴底）—— 流式追加时视口自动跟住新内容，不需要每帧手算滚动偏移；「贴底」判据是 `firstVisibleItemIndex <= 3`
- 工具输出/入参入库即截断（`capForDisplay`，输出 20k / 入参 6k 字符 + 尾部标注原始长度）。不截断的话单条 200KB Bash 输出塞进 Compose `Text` 会真的卡住布局（单 Text 长文本是 O(n)）
- **正文按 Markdown 渲染**（0.10.0 起，见 §15）：`ui/Markdown.kt` 自研子集解析器，不引 markdown 依赖；助手正文 / 思考过程 / 工具输出都走同一套渲染，代码块带复制按钮；**未闭合围栏也渲染**（流式输出中间态就是没闭合的）
- 三种待处理交互（`prompt.ask` / `prompt.permission` / `prompt.approve`）统一走 `respondPending`：**无论成败都 `clearPending()`** —— 服务端对过期请求回 404，只在成功时清卡片会让用户被一张永远点不掉的卡片卡住（SSE replay 也可能带出旧请求）
- 文档审核「驳回」必须带非空 `comment`（服务端 schema 强制 1..2000），UI 固定填「手机端驳回」；批准可不带
- **不确定图标存不存在就直接查，别猜名字**（写错编译就红，但改一次要一轮构建）：
  ```bash
  J=$(find ~/.gradle/caches -name "material-icons-extended-*-runtime.jar" | head -1)
  unzip -l "$J" | grep -E "filled/(GraphicEq|ArrowUpward)Kt.class"
  ```
  `Add` / `Close` / `Check` / `Refresh` / `MoreVert` / `KeyboardArrow*` 这些在 **material-icons-core**（另一个 jar）；`Stop` / `GraphicEq` / `ArrowUpward` / `AddPhotoAlternate` / `ContentPaste` / `SmartToy` / `OpenInNew` 在 extended。

**输入条：WorkBuddy 双行白卡（0.10.1 定稿）**。演进过程：0.9.2 是「独立输入框 + 框外圆按钮」（两套圆角、窄屏压掉文本框）→ 0.10.0 改成单胶囊（语音 / 文本 / 右按钮同一行）→ **0.10.1 定稿为双行白卡**，因为 WorkBuddy 的输入区是「上排纯文本域 + 下排工具条」，不是一行的胶囊：

```
┌────────────────────────────────────────────┐
│ 输入消息…                                   │   ← 第一行：BasicTextField 独占整行（maxLines=6）
│ (波形)  ◍ deepseek-v4.1 ⌄   (＋)      (➤)  │   ← 第二行：工具条，发送钮贴右
└────────────────────────────────────────────┘
```

- **卡**：`Surface(RoundedCornerShape(24.dp))` 白底 + `shadow(2.dp)`，**无描边**（描边会让它像输入框而不是卡片）；左右外边距 16dp
- **第二行内容**（左 → 右）：语音图标（`voice.available` 才渲染）→ 模型 chip（`ModelChip`：图标 + 别名 + `⌄`，别名 `.widthIn(max = 116.dp)` 省略号）→ `+`（打开附件/粘贴面板）→ `Spacer(weight(1f))` → 发送/停止圆钮
- **发送钮常驻、只有颜色变**（`InputBarCircle`，外层 40dp / 内层 38dp）：空输入 = 浅蓝灰 `#E0E3E8` + 白箭头（禁用，按下无反应）；有内容 = 品牌平安橙；运行中 = `error` 实心圆 + 停止图标。**不要**再回到「空输入时把发送钮换成 `+`」——那样按钮会随输入状态跳变，WorkBuddy 是 `+` 与发送钮**并存**
- **卡下方的 icon row（图片/粘贴/模型/更多）已删除**：WorkBuddy 没有这一行。功能没丢 —— 图片/粘贴收进 `+` 的面板，模型走卡内 chip
- 附件缩略图挂在白卡**上方**（横向可滚），不挤占输入宽度

**「按住说话」走云 ASR**（`voice/` 包）。与上面的 `SpeechRecognizer` **并存**，由 `local.properties` 开关切换：

| | 腾讯云实时 ASR | 复用 WorkBuddy 登录态 |
|---|---|---|
| 开关 | `asrAppId`/`asrSecretId`/`asrSecretKey`，或 `asrSignViaBackend=true` | `asrUseWorkBuddy=true` + `asrWbAccessToken` |
| 鉴权 | URL 签名（HMAC-SHA1，无登录环节） | `Authorization: Bearer <Keycloak JWT>` + `X-User-Id` |
| 收尾 | 文本帧 `{"type":"end"}` | **空二进制帧** |
| 下行 | `result.slice_type` 切片（1 非稳态 / 2 稳态） | **整段全量 `text`，覆盖，不能追加** |

- 判定顺序在 `voice/VoiceAsrConfig.kt`：后端签发 → WorkBuddy 直连 → 腾讯云自签 → 都没配就回落 `SpeechRecognizer`（行为跟加之前一致）。
- 协议差异抽在 `AsrDialect { TencentCloud, WorkBuddy }`；采集与上行两家一致（16k/mono/PCM16、100ms 一片、裸二进制帧）。
- WorkBuddy 那条的凭据位置/续期/风险见 [`docs/superpowers/specs/2026-09-14-workbuddy-api-token-applicability.md`](docs/superpowers/specs/2026-09-14-workbuddy-api-token-applicability.md)（含 JWT claims 解码、`acc-product-config-v3.json` 配置、app.asar 反编译出的端点表、`copilot.tencent.com` 真机探测矩阵）。⚠️ 它的 refresh_token 是**一次性轮换**的，别在桌面端和后端同时刷，会把桌面端踢下线。**通用结论**：同一把 JWT 在 `copilot.tencent.com` 下覆盖绝大多数业务接口（对话/定时任务/连接器/配额/自定义技能/项目等），仅续期/切账号走 `X-Refresh-Token`、`/console/as/*` 管理面被 403、pre-login 流程需 `X-No-Authorization: true`。
- 为什么非绕开 `SpeechRecognizer` 不可：国行无 Google 服务的 ROM 上 `isRecognitionAvailable()` 恒为 false，按钮**直接不渲染**（`if (voice.available)`）—— 云 ASR 没这个依赖。

**语音输入走平台 `SpeechRecognizer`**（`ui/VoiceInput.kt`），不引第三方 SDK：零依赖 / 零 key / 零体积，代价是必须联网且设备得真有识别服务。三个必须知道的点：

1. **Manifest 必须写 `<queries><intent action="android.speech.RecognitionService">`**（targetSdk 30+ 包可见性）。漏掉会在真机上**稳定** `isRecognitionAvailable() == false`，且不报任何错 —— 表现为「语音按钮永远点不动」。
2. **`SpeechRecognizer` 只能在主线程创建/调用**（内部要绑 Service）。Compose 的 `remember` / 点击回调都在主线程，所以不切线程，但也别挪到协程里。
3. **`onError(ERROR_CLIENT)` 是噪音**：主动 `stopListening()` / `cancel()` / `destroy()` 都会回调它，弹「识别失败」很蠢 → 静默吞掉。
4. **`stop()` 之后仍会异步回调 `onResults`** —— 点发送时要调 `discard()`（内部 `cancel()` + `discardResults` 标志）而不是 `stop()`，否则刚发出去的话会被识别结果重新填回已清空的输入框，看着像「发出去的话又回来了」。
5. `onPartialResults` 给的是**累积整句**，所以是覆盖回填（`baseText + partial`）而不是追加。

**图片附件统一重编码成 JPEG**（`data/ImageAttachment.kt`，长边 1600 / Q85 / 白底铺平）。三条理由缺一不可：

- 服务端 `ImageBlock.source.media_type` 是**枚举** `image/jpeg|png|gif|webp`（agent.ts:216-221）—— 相册里的 HEIC / BMP / AVIF 原样传会被 zod 拒成 400
- 紧跟一道 magic bytes 预检（agent.ts:1906-1914）：声明的 media_type 与字节头不一致就 400 `image_format_mismatch`，而 `content://` 的 MIME 在部分 ROM 上会撒谎
- `express.json({ limit: '20mb' })`（server/index.ts:171）是整包上限，现在手机随手一张 4–12MB，base64 再 ×1.33 直接顶格

重编码后 media_type 恒为 `image/jpeg`，单张 200–500KB。**白底铺平那步不能省**：PNG 截图带 alpha，`compress(JPEG)` 会把透明区压成黑色，深色主题下像图坏了。

**`contentBlocks` 里只能放图片块，文本必须留在顶层 `prompt`**。服务端自己拼 user content：`blocks.length ? [...blocks, ...(text ? [{type:'text',text}] : [])] : text`（agent.ts:1201-1204）。客户端"贴心"地再加一个 text block → 用户看到自己发的话重复两遍。这条契约钉在 `AgentApiBodyTest` 里。只发图（空 prompt）是合法的，此时**不传** `prompt` 字段而不是传空串。

**顶栏只留「返回 + 标题 + 副标题」**：刷新 / 在网页打开 / 全部会话元信息都收进**副标题点开的 BottomSheet**（`SessionInfoSheet`），状态标签只在非空闲时出现。对齐 WorkBuddy 的顶栏密度 —— 之前塞了 4 个 action，标题被挤得只剩几个字。

**限制（已知）**：超大会话（实测有 13MB / ~1300 条消息的 jsonl）hydrate 时要把整份 JSON 读进内存解析，峰值可能到几十 MB；极端长会话在低端机上可能 OOM。会话列表轮询 5s，本身不做 SSE（实时性由详情页负责）。图片附件图片张数上限 4（`ImageAttachments.MAX_COUNT`），因为 `AttachedImage` 持有 base64 常驻内存。

### 15. WorkBuddy 视觉体系 + Markdown 渲染（0.10.0 / 0.10.1 精修）

**为什么改**：0.9.x 用的是 Material You 动态取色（`dynamicColor = true`），配色跟着手机壁纸跑 —— 真机截图整屏泛紫，跟本项目一直对标的 WorkBuddy 完全不是一个东西。0.10.0 把视觉体系钉成一套固定 token。

**色板（唯一来源 `ui/LanAgentTheme.kt`）**：数值取自两处硬证据 —— WorkBuddy 官方图标
（青绿渐变，`ascii` 采样 `#0DC8A6 → #14CA85`）和 WorkBuddy 手机端截图采样。

| 槽位 | 亮色 | 深色 | 用途 |
|------|------|------|------|
| `background` **=` `surface`** | `#F8F8F8` | `#141517` | 页面底 + **顶栏**(所以顶栏不再是白条) |
| `surfaceContainerLow/Lowest/Container/High` / `surfaceBright` | `#FFFFFF` | `#1F2124` | 卡片 / 输入条 / 弹层 / 对话框 |
| `surfaceContainerHighest` | `#F3F4F6` | `#26282C` | 代码块 / 未选中项(唯一比卡片深一档的槽) |
| `onSurface` / `onSurfaceVariant` | `#1F1F1F` / `#8C8C8C` | `#ECEDEF` / `#9AA0A8` | 正文 / 次要文字 |
| `primary` | `#ff6600` | `#35D6B6` | 主按钮 / 发送按钮 / 运行中 |
| `tertiary` | `#E2932F` | `#F0B160` | 「运行中」工具卡的强调色 |
| `outlineVariant` | `#EBEDF0` | `#2B2D31` | 卡片发丝描边 |

**M3 槽位装不下的两个色（0.10.1）**：用户气泡底色、发送钮禁用态底色**没有**对应的
M3 语义槽（硬塞 `surfaceVariant` 会连带改掉代码块底色等无关位置），所以单开一个
`LocalWbExtras`（`ui/LanAgentTheme.kt`，`@Immutable data class WbExtras`），由
`LanAgentTheme` 用 `CompositionLocalProvider` 提供：

| 字段 | 亮色 | 深色 | 用途 |
|------|------|------|------|
| `userBubble` | `#E2E4E3` | `#2A2D2C` | 用户消息气泡 |
| `sendDisabled` | `#E0E3E8` | `#34383D` | 发送钮禁用态 |

**用户气泡：中性浅灰，不是品牌绿（0.10.1 修正）**。0.10.0 用 `primaryContainer`（薄荷绿
`#DDF6F0`）+ 右下角 4dp 小尖角，跟 WorkBuddy 放在一起一眼就能看出不是一个产品。
WorkBuddy 手机端采样结果：气泡底 **`#E2E4E3`**（中性灰）、正文 `#1F2120`、**四角同半径
18dp（没有 IM 那种尖角尾巴）**、长文可以占到接近满宽（**不设 320dp 上限**）。
品牌平安橙只留给发送按钮/主按钮 —— 这是 WorkBuddy 与「绿色气泡 IM」的分水岭。

**两个关键设计**：

1. **`surface` 直接设成页底色，卡片族全设成白色** —— 于是 `Scaffold` / `TopAppBar`
   默认取 `surface`（灰，跟页面连成一片），`Card` / `ModalBottomSheet`
   （`surfaceContainerLow`）/ AlertDialog / 会话行 / 工具卡（`surfaceContainerHigh`）
   默认取白色。全项目一百多处 `MaterialTheme.colorScheme.*` 调用一次性对齐，
   不用逐个屏幕改颜色。
2. **`dynamicColor` 默认关**（参数保留，默认 `false`）。真要开 Material You 才显式传 `true`。
3. **状态栏图标明暗自适应**：状态栏透明 + 内容画到状态栏后面，所以 `LanAgentTheme`
   里用 `WindowInsetsControllerCompat.isAppearanceLightStatusBars = !darkTheme` 跟着页面深浅切，
   否则浅色页面上白图标 = 隐形状态栏。

**字号阶梯**：`WbTypography` 只覆盖高频档位 —— 顶栏标题 `titleMedium` 16sp/SemiBold、
正文 15sp/22sp、次要 12sp、说明 10–11sp。Markdown 内部另有一套（h1 21sp → h4 15.5sp，
代码 12sp/18sp 等宽），见下。

**机器人形象 / 图标**：
- 素材来源：从 WorkBuddy 桌面端 `app.asar` 里抽出来的官方资源
  （`renderer/assets/mascot-new-*.png`，1080×1038 RGBA，透明底）。
- `drawable-nodpi/wb_mascot.png` = 去白边 + 缩到 640px，给空态用（会话空态 168dp、
  首页空态 132dp）。
- 启动图标 = 品牌平安橙底 + **机器人头**（从 mascot 上半部裁的头部，`app/src/main/res/mipmap-*`
  各密度 PNG + `drawable-nodpi/ic_launcher_foreground.png` 自适应前景）。
  自适应前景按 108dp 画布的 **66dp 安全区**（内容 ≤61%）留白（`pad = 0.20`），
  这样任意启动器遮罩下耳朵都不会被切。
- 空态问候语「LAN Agent,我帮你」照搬 WorkBuddy 的「XXX,我帮你」句式，
  文案在 `strings.xml` 的 `agent_session_empty_title`，改文案只动这一处。

**Markdown 渲染（`ui/Markdown.kt`）**：

- **不引第三方库**：通用 markdown 库（多平台版）会带语法高亮 / 数学公式 / HTML 子集，
  体积几 MB 而本项目只需要一个子集。
- **解析与渲染分离**：`MarkdownParser.parse(String): List<MdBlock>` 是**纯 Kotlin 函数**
  （不 import 任何 Compose 类），渲染层只做 `when (block)` 映射。
  以后想加单测 / 换渲染实现都只动一层。
- **覆盖范围**：块级 `#{1,6}` / 段落 / ``` 围栏代码 / `>` 引用 / `-`·`1.` 列表(嵌套 +
  `- [x]` 任务框) / 表格 / `---` 分割线；行内 `**粗**` `*斜*` `` `码` `` `~~删~~`
  `[文字](url)` 裸链接 `\` 转义。行内是**递归下降**，所以「粗体里套行内码」天然支持。
- **链接点击**：用 Compose 1.7 的 `LinkAnnotation.Url` + `TextLinkStyles`，
  `Text` 自动走 `LocalUriHandler` 打开系统浏览器 —— 不需要 `ClickableText` / 手动命中测试。
- **流式安全（两条硬约束）**：
  1. **未闭合的围栏**直接当代码块渲染到结尾 —— 不能把后面所有内容吞进代码块后再也不吐出来；
  2. **找不到闭合标记的行内标记**（半截 `**` / 半截 `` ` ``）原样输出，不能吞掉后面的字。
  这两点决定了「先 hydrate 再 SSE 逐字渲染」的观感，改动解析器时必须守住。
- **表格**：整表包一层 `horizontalScroll`，列宽 `widthIn(min = 96.dp)` —— 窄屏横向滚动
  而不是把每列挤成竖排单字。
- **`CodeBox` 也住在 Markdown.kt**（工具卡 / 权限卡 / 文档审核卡都在用）：
  等宽 + 独立底框 + 横滚 + 右上角「复制」。
- **`MarkdownText(compact = true)`** 给思考过程这类副文本用（块间距 4dp 而不是 7dp，
  配 12sp 次要文字色）。

**改配色 / 改字号 / 换机器人形象要动哪里**：
配色 → `ui/LanAgentTheme.kt` 的 `WbPalette` + 两个 scheme；字号 → 同文件的 `WbTypography`；
空态文案 → `strings.xml`；机器人图 → 覆盖 `drawable-nodpi/wb_mascot.png`；
启动图标 → 覆盖 `mipmap-*` 各密度 PNG + `drawable-nodpi/ic_launcher_foreground.png`
（`values/colors.xml` 的 `ic_launcher_background` 是底色）。

## 强制开发规则


- **JAVA_HOME 必须显式设**:`/usr/libexec/java_home` 在这台机器上是 broken,直接用:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```
- **dl.google.com 不可达** — `settings.gradle.kts` 加了 Tencent Maven mirror。普通网络用户可移除。
- **Android SDK 在 `/Users/ethan/Library/Android/sdk`**,`local.properties` 已 gitignore。
- **JDK 21 替代 JDK 17**:AGP 8.6.1 支持,JDK 17 没装,`compileOptions` 仍保持 `VERSION_17` bytecode target。
- **JDK 升级 / 依赖升级**:version catalog 锁版本,升级单独跑一次。
- **不要给 release 写 minify / signing**(spec §2.2)— `isMinifyEnabled = false`,`proguard-rules.pro` 是空文件。

## 常用命令

```bash
# 编译
cd /Users/ethan/code/lan-agent
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :app:assembleDebug         # 出 APK 到 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # 装到当前 adb 设备

# 本地起 serve,手机扫码下载
npx serve -l tcp://0.0.0.0:8765 app/build/outputs/apk/debug/
# 手机访问 http://<本机 LAN IP>:8765/

# bump version
# 改 app/build.gradle.kts: versionCode / versionName

# 重置 DataStore(回 seed)
adb shell pm clear io.github.hotmanxp.lanagent
```

## 改入口卡片

有 5 种方式:

1. **APP 内编辑模式** — 点首屏右上 `✎` 进 edit mode,长按拖拽换位,点 `🗑` 删除,点 `+` 加新卡。改完即时写 DataStore,下次启动还是这些。
2. **APP 内添加实例** — 首屏右上 `Storage` 进 InstancesScreen → 右下 `+` 浮动按钮 → 选手动表单 / 目录选择 / QR 扫码。**新加的是服务端实例定义,不是首页 Card**;首页 Card 列表独立于实例管理。
3. **QR 扫码进入** — 首屏右上 QR 图标 → 扫 zai 分享的 URL → 直接跳 WebView。**不进实例管理,也不写 DataStore**。
4. **SSH 一键启动 zai** — 首屏右上 `Memory` 图标 → SshHostListScreen → 加一条 SSH host(name/IP/22/Mac 用户名/密码/`zaiPort` 9201) → 点「启动 zai」→ 全局 `nohup zai --lan --port <zaiPort>` 远程拉起,端口可达后自动跳 InstancesScreen WebView。
5. **改 seed 卡片** — 编辑 [`app/src/main/java/io/github/hotmanxp/lanagent/data/Cards.kt`](app/src/main/java/io/github/hotmanxp/lanagent/data/Cards.kt) 里的 `defaultCards` 列表,改完 `./gradlew :app:installDebug` 重装即可。**只影响卸载重装后的首次启动**(已有数据从 DataStore 读)。

如目标 IP 不在白名单,还要编辑
[`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml)
加一行 `<domain includeSubdomains="true">你的.IP</domain>`(默认 base-config 已经全放行 cleartext,这一步多半不需要)。

## 已知坑 / 经验

| 坑 | 现象 | 解法 |
|----|------|------|
| `webView.setBackgroundColor(...)` 不生效 | WebView 内容周围还是白底 | 用 Compose `Box.background()` 画底色,WebView 设 TRANSPARENT |
| `useWideViewPort = false` + web 有 `<meta viewport>` | 页面按 980px 渲染,看起来元素过大 | 必须 `useWideViewPort = true` |
| `dl.google.com` 超时 | `:app:mergeDebugGlobalSynthetics` 卡死 | `settings.gradle.kts` 已加 Tencent mirror,新机器要手动加 |
| `(-8).dp` 负 Dp | `IllegalArgumentException` 闪退 | 用 `windowInsetsPadding(...)` + `padding(top = 4.dp)`,不要负 Dp |
| `IconButton.size(28.dp)` 强制 48dp | 浮动刷新按钮变 48dp 而不是 28dp | 用 `Box + Modifier.clickable`,不绕 IconButton 的 minimumInteractiveComponentSize |
| Android 11+ gesture bar 不消失 | 看到底部一条细线 | 系统限制,只能 swipe 唤起后自动隐;要彻底隐需切 3-button nav |
| Wi-Fi IP 变了 | 卡片 URL 全部失效 | 编辑模式 → 点卡 → 改 URL;或改 `data/Cards.kt` 重 build |
| zai 9201 listen localhost | 手机访问 `192.168.x.x:9201/instances` 拒连 | `pnpm --filter @zn-ai/zai dev -- --lan` 重启 zai 绑 0.0.0.0 |
| OEM ROM `params.createIntent()` 没带 `FLAG_GRANT_READ_URI_PERMISSION` | 选图后 WebView 拿不到 bytes,`<input>.files` 为空 | 手动构建 pickIntent + `Intent.createChooser`,绕过 `params.createIntent()` |
| WebView `onShowFileChooser` 对 content:// URI 转换不可靠 | FileReader.readAsDataURL 拿不到字节 | 走 `window.lanAgentAttachImages` bridge,直接把 base64 注入 `<input>` |
| Activity.onPause 冻结 WebView 网络栈 | 切走再回来 SSE / WebSocket 全断 | `WebViewKeepAliveService`(detached WebView + dataSync foreground)+ `DisposableEffect(url)` 启停 |
| 服务忘 stop 把电池榨干 | WebViewScreen 跳走但服务没收到 onDispose | 30 分钟 `PARTIAL_WAKE_LOCK` acquire(timeout) 兜底,过期自动释放 |
| Android 14 (API 34) 启 dataSync 服务 SecurityException | `startForeground(2-arg)` 抛异常 | 用 3-arg `startForeground(NOTIF, notif, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` |
| 通知 channel MIN 重要性被 MIUI 完全隐藏 | 用户看不到后台运行通知 | channel 用 IMPORTANCE_LOW(不算最小,不算骚扰) |
| 没声明 `FOREGROUND_SERVICE_DATA_SYNC` | API 34+ startForeground SecurityException | AndroidManifest.xml 加 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` |
| 没在首页添加「实例管理」入口 Card | 点 Storage 按钮弹"未配置实例管理入口卡片"对话框 | 加一张 `url = "http://host:port/instances"` 的 Card(参考 `defaultCards` 第 0 张) |
| `down` 状态心跳超时但不到 3 分钟 | "启动"按钮不可点 | 用 `effectiveState()` 把超过 `STALE_THRESHOLD_MS = 3min` 的 `down` 视为 `stopped` |
| IME 弹出时 WebView 不让出 | 输入框被键盘盖住看不到 | `AndroidView.modifier.imePadding()` 让出软键盘 |
| SSH exit 127 (`command not found`) | `which zai` 在 Mac 上返回空 | `npm i -g zai` 全局装,或 `~/.zshenv` 加 `export PATH="/opt/homebrew/bin:$PATH"` |
| SSH 启动 zai 报 `EADDRINUSE` | 默认 9201 被另一个 supervisor 占 | 改 SSH host 配置的 `zaiPort`(默认 9201 可改成 9202/9203),或停掉那个 supervisor |
| SSH host DataStore 损坏 | 启动后 SshHostListScreen 为空 | `adb shell pm clear io.github.hotmanxp.lanagent` 会**同时清掉 cards/ui_prefs/ssh_hosts**;只清 ssh 用 `pm clear --user 0 io.github.hotmanxp.lanagent` 后改 DataStore name |
| WebView settings 在 foreground / background 漂移 | 切走再回来 SSE 重连定时器悄悄重置 | 用 `service/WebViewFactory.kt` 单源,不要在 `WebViewScreen` 和 `WebViewKeepAliveService` 各写一遍 |
| 浮刷新按钮位置不持久 | 每次启动按钮都在默认位置 | 检查 `data/UiPrefsRepository.kt`(独立 DataStore `lan_agent_ui_prefs`)+ `WebViewScreen` 是否有 `LaunchedEffect(Unit)` 读 `readRefreshButtonPos()` |
| 会话详情页整个打不开(报反序列化错) | `GET /api/agent/sessions/:id` 返回后解析失败 | `TranscriptEntry.timestamp` 必须声明 `JsonElement?` —— transcript 里 `system` / `queue-operation` 条目的 timestamp 是 ISO **字符串**(其余条目是数字)。声明成 `Long` 时一条 system 就能让整份 transcript 解码失败 |
| SSE 连上后收不到权限/队列事件 | 审批卡、队列条永不出现 | 不要给 `/api/event` 传 `topics` —— 白名单里没有 `prompt.approve` / `prompt.permission` / `queue.changed`,只传 `?sid=` |
| 重连后消息重复 | 切后台再回来,历史气泡翻倍 | 服务端 `Last-Event-ID` 按 `eventId` 匹配、而 `id:` 行写的 `seq`,永远 miss → 每次都全量 replay。客户端必须按 `seq` 单调去重(见 `AgentApi.eventStream`) |
| 退出会话页后日志里还在反复重连 | 后台线程永不退出,漏 socket | 只 `call.cancel()` 不够(`Call.cancel()` 不是线程中断,异常被 catch 吞掉后循环会重新建连)。`awaitClose` 里必须同时置 `cancelled` 标志,循环/catch/退避三处都看它 |
| 自己刚发的消息不显示 | 发完消息气泡不出现 | 服务端 SSE **没有**「用户消息」类事件,必须 `AgentStore.appendLocalUser()` 本地乐观追加 |
| 工具卡之后的助手文本跑到工具卡前面 | 消息顺序错乱 | 工具卡 upsert 时清掉流式气泡游标(`curTextIdx` / `curThinkIdx`) |
| 权限/审批卡点不掉 | 点批准弹 404 后卡片还在 | `respondPending` 必须**无论成败**都 `clearPending()`,服务端对过期请求回 404 |
| 打开大会话卡顿/OOM | 详情页转圈很久或崩溃 | transcript 是整份 JSON 一次读入;实测有 13MB / ~1300 条消息的会话。工具输出已按 20k 字符截断入库,再大只能靠服务端侧分页(未做) |

## 配套:opencc-web 端

lan-agent 是消费者,opencc-web 是服务方。opencc-web 那侧需要:

- `pnpm --filter @zn-ai/zai dev -- --lan` 启动,绑 0.0.0.0(zai 默认端口 9201 / MobileAgent 路由 8101)
- zai 的 mobile Agent 路由 `/m`(`packages/zai/src/web/src/pages/MobileAgent.tsx`)
- zai 的实例管理路由 `/instances`(`packages/zai/src/web/src/pages/Instances.tsx`) + `/api/instances` + `/api/fs/picker`
- **运行时只剩 `repl` 一种形态**:opencc-web 阶段 3(2026-09-12)删除 `RuntimeCore` 类型 / `runtimeCore` 字段 / `--runtimeCore` CLI flag / `PUT /api/agent/settings/runtime-core` 端点;lan-agent 的 `InstanceRuntimeCore` 枚举已同步移除(见 `data/InstanceModels.kt` 历史字段说明)

**两种启动 zai 的方式**:
1. **桌面手动**:`pnpm --filter @zn-ai/zai dev -- --lan`(opencc-web 仓库内,会拉 monorepo deps)
2. **SSH 一键**(lan-agent 的 0.7.x 功能):全局装 `zai` 二进制后,从手机 SSH 到 Mac 执行 `nohup zai --lan --port <zaiPort>`,cwd 无关,适用于 zai 没起来的场景

opencc-web 仓库在 `/Users/ethan/code/opencc-web/`,详见 `opencc-web/AGENTS.md`。

## 版本 / 发布

- 当前: **0.10.3** (versionCode 41) — `style(ui): 运行态徽标左对齐 + 三点波浪动画 + 灰底`
- 上一版: **0.10.2** (versionCode 40) — `style(theme): 亮色主题品牌色改回平安橙 #ff6600(深色不变)`
- 再上一版: **0.10.1** (versionCode 39) — `style(ui): 用户气泡改中性浅灰 + 输入条改 WorkBuddy 双行白卡`
- 不发 release,只本地 debug APK
- 每次改完手动 bump `versionCode` + `versionName`(`app/build.gradle.kts`),否则手机装上后版本号不变看不出是新版
- 历史里程碑:`0.1.1` (WebView 基础) → `0.1.2/0.1.3/0.1.4` (WebView 边距/icon) → `0.6.0` (多实例管理 + 后台保活 + 文件上传) → `0.6.2` (portrait 锁定) → `0.7.0` (SSH 启动 zai) → `0.7.1` (`--runtime` 选项) → `0.7.2`(`kernel` → `runtimeCore` 重命名) → `0.7.3`(`runtimeCore` 加 `repl` 枚举值) → `0.8.0`(实例类型 `app` profile:标准 / 任务工厂 `task-factory`,对齐 opencc-web `InstanceDefinition.app`) → `0.8.1`(`InstanceAppProfile` 加 `Weixin` 防止反序列化崩溃 + 卡片 `WeixinTag`) → `0.9.0`(**原生 Agent 会话**:会话列表 + 会话详情,直连 `/api/agent/sessions` + `/api/event` SSE,支持发消息/中断/队列 steer/权限确认/问询/文档审核;实例卡加「会话」动作,动作行改可横滚) → `0.9.1`(修 `updatedAt` 浮点导致会话列表整页报错打不开;建 JVM 单测基建 `app/src/test/`) → `0.9.2`(**输入条对齐 WorkBuddy**:单胶囊三态(语音/文本/发送·停止·`+`)、系统 `SpeechRecognizer` 语音转文字、图片附件(Photo Picker → 重编码 JPEG → `contentBlocks`)、顶栏瘦身(刷新/分享收进副标题面板)、空态改大图标+文案)
- 详细开发产物见 `docs/superpowers/specs/2026-08-24-lan-agent-android-app-design.md`(原 v0.1 spec)+ `docs/superpowers/plans/2026-08-24-lan-agent-android-app.md`(10-task 实现 plan)+ `docs/superpowers/specs/2026-09-14-workbuddy-api-token-applicability.md`(WorkBuddy accessToken 适用面调研,含真机探测矩阵)。**注意**:spec/plan 在 0.6.0 / 0.7.x 大幅扩展后已过期,但作为初始设计参考仍可读;后续新增功能没再写独立 spec/plan,只有 0.10.x 的 ASR 路线在 2026-09-14 这份调研里留下了 WorkBuddy 鉴权与端点适用面的最新事实底座。