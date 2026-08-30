# AGENTS.md — `/Users/ethan/code/lan-agent/`

> **lan-agent** — 简单 Android App,把局域网内多个 opencc-web 实例的入口收成卡片列表,点击卡片进入 WebView 详情加载对应 URL;同时**原生**展示 zai 实例管理 API(启动/停止/重启/删除/打开/二维码扫码添加);**SSH 一键启动 zai**(当 Mac 没起来 zai 时)。配套工程是 `/Users/ethan/code/opencc-web`,zai 需用 `pnpm --filter @zn-ai/zai dev -- --lan` 启动才能让手机访问(SSH 模块则全局 `zai --lan --port <p>` 启动)。
>
> **当前 HEAD**: `b5c43ae` on `main` · **versionCode 30** · **versionName 0.7.3** · **HEAD message**: `feat(instances): add repl to runtimeCore enum + bump 0.7.3`

## 仓库用途

`/Users/ethan/code/lan-agent/` 是一个**独立顶级目录、独立 git 仓库**,**不在 opencc-web monorepo** 内。spec / plan 文档在 `docs/superpowers/{specs,plans}/`。

## 目标 / 非目标

### 目标

- 单 Activity + Jetpack Compose + Navigation Compose
- 首屏卡片列表(数据来源: 写死的 `defaultCards` + DataStore 运行时增删改)
- **原生实例管理屏**(`InstancesScreen` + `InstanceCard`):直连 `/api/instances` 拉快照,2.5s 轮询
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
│   └── plans/2026-08-24-lan-agent-android-app.md
└── app/
    ├── build.gradle.kts              # compileSdk 34 / minSdk 26
    ├── proguard-rules.pro             # 空(debug-only)
    └── src/main/
        ├── AndroidManifest.xml        # 单 Activity + 6 类权限 + service
        ├── res/
        │   ├── values/{strings,themes,colors}.xml
        │   ├── xml/network_security_config.xml   # base-config cleartextTrafficPermitted="true"
        │   ├── mipmap-anydpi-v26/                 # adaptive icon(Wi-Fi hub 风格)
        │   ├── mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/
        │   └── drawable/ic_launcher_{background,foreground}.xml
        └── java/io/github/hotmanxp/lanagent/
            ├── MainActivity.kt        # setContent + immersive + 媒体权限申请
            ├── LanAgentApp.kt         # Application;注册 WebViewKeepAlive 通知 channel
            ├── ui/
            │   ├── LanAgentTheme.kt   # Material3 + dynamicColor
            │   ├── AppNavHost.kt      # NavHost: home / scan / instances/{baseUrl} / webview/{url}
            │   ├── HomeScreen.kt      # 首页卡片列表 + 4 按钮(scan/instances/edit/add)+ 编辑模式(增删改拖拽)
            │   ├── EditCardDialog.kt  # 旧卡片增改对话框(HomeScreen 用)
            │   ├── WebViewScreen.kt   # 全屏 WebView + 文件上传 + Service 启停 + 已刷新 snackbar
            │   ├── InstancesScreen.kt # 原生实例管理(2.5s 轮询 + 5 动作 + 3 弹窗)
            │   ├── InstanceCard.kt    # 单张实例卡(状态 Tag + LAN Switch + 描述列表 + 动作行)
            │   ├── InstanceFormat.kt  # 运行时长 / 相对时间 / 时间戳格式化 helper
            │   ├── CreateInstanceDialog.kt  # 创建实例:名称/cwd/LAN/端口/内核
            │   ├── EditPortDialog.kt  # 编辑启动端口
            │   └── DirectoryPickerDialog.kt  # 文件系统目录选择器(拉 /api/fs/picker)
            ├── data/
            │   ├── Cards.kt           # 5 张 hardcode 默认卡片 + findManagerBaseUrl
            │   ├── CardRepository.kt  # DataStore 持久化(`lan_agent_cards`)+ resetCards()
            │   ├── UiPrefsRepository.kt  # 浮按钮拖拽位置持久化(`lan_agent_ui_prefs`)
            │   ├── SshRepository.kt   # SSH host DataStore(`lan_agent_ssh_hosts`)
            │   ├── InstanceModels.kt  # InstanceSnapshot/State/RuntimeCore + FsPickerEntry
            │   └── InstancesApi.kt    # OkHttp 客户端 + PatchValue 三态
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
- 单卡片 `InstanceCard` 视觉对标 web `Instances.tsx`:name + 当前 Tag + 状态 Tag(stopped/starting/running/stopping/down 五态配色对齐 web)+ LAN Switch + **runtimeCore Tag(default/inproc/spawn/repl/继承全局)**+ 启动端口(可编辑)+ 运行端口/cwd/PID/启动时间/运行时长/创建时间/最后心跳/错误 + 5 个动作按钮(启动/停止/重启/删除/打开)
- **`down` 超过 3 分钟视作 `stopped`**(`STALE_THRESHOLD_MS`),让"启动"按钮可点(对齐 web 端 effectiveState)
- 30s 一次的 `now` tick — 让运行时长 / 相对时间不卡在同一数字
- 操作防抖:`lanBusy` / `actionBusy` 两个 `mutableStateListOf<String>` 记 instanceId,按钮转圈
- 三种创建方式:
  - **手动表单**(`CreateInstanceDialog`):name / cwd(可点"浏览"拉 DirectoryPicker)/ LAN checkbox / 端口(自动/手动)/ **runtimeCore(default/inproc/spawn/repl/继承全局,0.7.3 新增 `repl`)**
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

### 13. zai 实例 runtimeCore 枚举(对齐 opencc-web)

`data/InstanceModels.kt` 的 `InstanceRuntimeCore { default, inproc, spawn, repl }` 对齐 opencc-web `packages/zai/src/shared/settings.ts` 的 `CoreRuntime = 'default' | 'inproc' | 'spawn' | 'repl'`(`0.7.3` 新增 `repl`)。`CreateInstanceDialog` 的 `--runtime` 选项提供选择;`null` 表示继承全局 settings.json 的 `coreRuntime` 字段。

历史命名:0.7.1 及以前叫 `InstanceKernel`(对应 `kernel` 字段),0.7.2 重命名为 `InstanceRuntimeCore`(对应 `runtimeCore` 字段)— 与 web 端 `coreRuntime` 字段对齐。**新代码用 runtimeCore**。

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

## 配套:opencc-web 端

lan-agent 是消费者,opencc-web 是服务方。opencc-web 那侧需要:

- `pnpm --filter @zn-ai/zai dev -- --lan` 启动,绑 0.0.0.0(zai 默认端口 9201 / MobileAgent 路由 8101)
- zai 的 mobile Agent 路由 `/m`(`packages/zai/src/web/src/pages/MobileAgent.tsx`)
- zai 的实例管理路由 `/instances`(`packages/zai/src/web/src/pages/Instances.tsx`) + `/api/instances` + `/api/fs/picker`
- **运行时核心枚举**(`packages/zai/src/shared/settings.ts` 的 `CoreRuntime = 'default' | 'inproc' | 'spawn' | 'repl'`,`0.7.3` 新增 `repl`):lan-agent 的 `InstanceRuntimeCore` 必须保持与之一致;`null` 表示继承全局 settings.json 的 `coreRuntime`

**两种启动 zai 的方式**:
1. **桌面手动**:`pnpm --filter @zn-ai/zai dev -- --lan`(opencc-web 仓库内,会拉 monorepo deps)
2. **SSH 一键**(lan-agent 的 0.7.x 功能):全局装 `zai` 二进制后,从手机 SSH 到 Mac 执行 `nohup zai --lan --port <zaiPort>`,cwd 无关,适用于 zai 没起来的场景

opencc-web 仓库在 `/Users/ethan/code/opencc-web/`,详见 `opencc-web/AGENTS.md`。

## 版本 / 发布

- 当前: **0.7.3** (versionCode 30) — HEAD `b5c43ae`(`feat(instances): add repl to runtimeCore enum + bump 0.7.3`)
- 不发 release,只本地 debug APK
- 每次改完手动 bump `versionCode` + `versionName`(`app/build.gradle.kts`),否则手机装上后版本号不变看不出是新版
- 历史里程碑:`0.1.1` (WebView 基础) → `0.1.2/0.1.3/0.1.4` (WebView 边距/icon) → `0.6.0` (多实例管理 + 后台保活 + 文件上传) → `0.6.2` (portrait 锁定) → `0.7.0` (SSH 启动 zai) → `0.7.1` (`--runtime` 选项) → `0.7.2`(`kernel` → `runtimeCore` 重命名) → `0.7.3`(`runtimeCore` 加 `repl` 枚举值)
- 详细开发产物见 `docs/superpowers/specs/2026-08-24-lan-agent-android-app-design.md`(原 v0.1 spec)+ `docs/superpowers/plans/2026-08-24-lan-agent-android-app.md`(10-task 实现 plan)。**注意**:spec/plan 在 0.6.0 / 0.7.x 大幅扩展后已过期,但作为初始设计参考仍可读;后续新增功能没再写独立 spec/plan。