# AGENTS.md — `/Users/ethan/code/lan-agent/`

> **lan-agent** — 简单 Android App,把局域网内多个 opencc-web 实例的入口收成卡片列表,点击卡片进入 WebView 详情加载对应 URL。配套工程是 `/Users/ethan/code/opencc-web`,zai 需用 `pnpm --filter @zn-ai/zai dev -- --lan` 启动才能让手机访问。

## 仓库用途

`/Users/ethan/code/lan-agent/` 是一个**独立顶级目录、独立 git 仓库**,**不在 opencc-web monorepo** 内。spec / plan 文档在 `docs/superpowers/{specs,plans}/`。

## 目标 / 非目标

### 目标

- 单 Activity + Jetpack Compose + Navigation Compose
- 首屏卡片列表(数据来源: 写死的 `defaultCards` + DataStore 运行时增删改)
- WebView 详情页加载对应 URL(cleartext HTTP,因为是 LAN)
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
        ├── AndroidManifest.xml        # 单 Activity + INTERNET + usesCleartextTraffic="true"
        ├── res/
        │   ├── values/{strings,themes,colors}.xml
        │   ├── xml/network_security_config.xml   # base-config cleartextTrafficPermitted="true"
        │   ├── mipmap-anydpi-v26/                 # adaptive icon(Wi-Fi hub 风格)
        │   ├── mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/
        │   └── drawable/ic_launcher_{background,foreground}.xml
        └── java/io/github/hotmanxp/lanagent/
            ├── MainActivity.kt        # setContent + immersive(hide navigation bar, status bar 保留)
            ├── ui/
            │   ├── LanAgentTheme.kt   # Material3 + dynamicColor
            │   ├── AppNavHost.kt      # NavHost("home" / "webview/{url}")
            │   ├── HomeScreen.kt      # 卡片列表 + 长按拖拽 + 编辑模式(增删改)
            │   ├── EditCardDialog.kt  # 添加/编辑卡片对话框
            │   └── WebViewScreen.kt   # 全屏 WebView,无 App 顶栏
            ├── data/
            │   ├── Cards.kt           # 5 张 hardcode 默认卡片(改 IP 改这里)
            │   └── CardRepository.kt  # DataStore 持久化,JSON 序列化
            └── model/
                └── Card.kt            # @Serializable data class
```

## 关键设计决策

### 1. 入口数据 = 写死 seed + DataStore 持久化

- `data/Cards.kt` 里 hardcode 5 张默认卡片(默认指向 `192.168.101.69` 的 5 个 zai 实例端口)
- 首次启动 `Context.cardsFlow()` 读 DataStore;无 key → 返回 `defaultCards`
- 用户用 APP 内编辑模式增/删/改/拖拽 → 写入 DataStore
- 改 `Cards.kt` 不会影响已装用户的现存数据(只有卸载重装才回到 seed)

### 2. 网络: 全局放行 cleartext

`AndroidManifest.xml` 设 `android:usesCleartextTraffic="true"`,`network_security_config.xml` 的 `base-config cleartextTrafficPermitted="true"`。

这是 LAN 工具的合理取舍 — 之前试过白名单 `192.168.0.0` + `includeSubdomains="true"`,但 Android 对 IP + `includeSubdomains` 是 **exact match**,不扩展到整个 /16 段,白名单机制玩不转。

### 3. WebView 配置

```kotlin
settings.javaScriptEnabled = true
settings.domStorageEnabled = true
settings.useWideViewPort = true      // 接受 <meta viewport width=device-width>
settings.loadWithOverviewMode = false // 不强制 fit content
settings.textZoom = 85              // opencc-web /m 没 responsive typography,缩 15%
```

`onReceivedError` 是 **no-op**(LAN 工具 ERR_FAILED 太频繁,Snackbar 噪声)。

### 4. WebView 背景色陷阱

`webView.setBackgroundColor(...)` 在 hardware-accelerated surface 下是 no-op。深色背景必须**在 Compose 层画**:
```kotlin
Box(Modifier.fillMaxSize().background(Color(0xFF1F2937))) {
    AndroidView(factory = { webView.also { it.setBackgroundColor(TRANSPARENT) } }, ...)
}
```

### 5. 系统栏(immersive)

`MainActivity.onCreate`:
- `WindowCompat.setDecorFitsSystemWindows(window, false)` — edge-to-edge
- `WindowInsetsControllerCompat.hide(WindowInsetsCompat.Type.navigationBars())` — 隐藏**底部导航栏**
- `WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` — 边缘 swipe 临时显示
- **状态栏保留可见**(用户要看到时间/电池)
- 状态栏背景设 `Color.TRANSPARENT`

`WebViewScreen` AndroidView 加 `windowInsetsPadding(WindowInsets.statusBars)` + `padding(top = 4.dp)`,让出状态栏高度 + 微空间,避免 WebView 内容被状态栏文字遮挡。**不要用负 padding**(`(-8).dp`),某些 Compose 版本会抛 IllegalArgumentException。

### 6. WebView 顶栏

**无 App 顶栏**(40dp 自绘 auto-hide 顶栏在 0.1.0 实验后删除)。返回用 Android 系统手势 / 返回键(`BackHandler` 接管 — `webView.canGoBack()` 时 `webView.goBack()`,否则回 HomeScreen)。

## 强制开发规则

- **JAVA_HOME 必须显式设**:`/usr/libexec/java_home` 在这台机器上是 broken,直接用:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
  ```
- **dl.google.com 不可达** — `settings.gradle.kts` 加了 Tencent Maven mirror。普通网络用户可移除。
- **Android SDK 在 `/Users/ethan/Library/Android/sdk`**,`local.properties` 已 gitignore。
- **JDK 21 替代 JDK 17**:AGP 8.6.1 支持,JDK 17 没装,`compileOptions` 仍保持 `VERSION_17` bytecode target。
- **JDK 升级 / 依赖升级**:version catalog 锁版本,升级单独跑一次。

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
```

## 改入口卡片

有 3 种方式:

1. **改 `data/Cards.kt`** — 改 defaultCards 的 url/title/subtitle/accent,重 build,装到设备。**只影响卸载重装后的首次启动**(已有数据从 DataStore 读)。
2. **APP 内编辑模式** — 点首屏右上 `✎` 进 edit mode,长按拖拽换位,点 `🗑` 删除,点 `+` 加新卡。改完即时写 DataStore,下次启动还是这些。
3. **清空回 seed** — `Context.resetCards()`(代码里有,UI 未暴露)。要手动:卸载重装。

## 已知坑 / 经验

| 坑 | 现象 | 解法 |
|----|------|------|
| `webView.setBackgroundColor(...)` 不生效 | WebView 内容周围还是白底 | 用 Compose `Box.background()` 画底色,WebView 设 TRANSPARENT |
| `useWideViewPort = false` + web 有 `<meta viewport>` | 页面按 980px 渲染,看起来元素过大 | 必须 `useWideViewPort = true` |
| `dl.google.com` 超时 | `:app:mergeDebugGlobalSynthetics` 卡死 | `settings.gradle.kts` 已加 Tencent mirror,新机器要手动加 |
| `(-8).dp` 负 Dp | `IllegalArgumentException` 闪退 | 用 `windowInsetsPadding(...)` + `padding(top = 4.dp)`,不要负 Dp |
| Android 11+ gesture bar 不消失 | 看到底部一条细线 | 系统限制,只能 swipe 唤起后自动隐;要彻底隐需切 3-button nav |
| Wi-Fi IP 变了 | 卡片 URL 全部失效 | 编辑模式 → 点卡 → 改 URL;或改 `data/Cards.kt` 重 build |
| zai 9201 listen localhost | 手机访问 `192.168.x.x:9201/instances` 拒连 | `pnpm --filter @zn-ai/zai dev -- --lan` 重启 zai 绑 0.0.0.0 |

## 配套:opencc-web 端

lan-agent 是消费者,opencc-web 是服务方。opencc-web 那侧需要:

- `pnpm --filter @zn-ai/zai dev -- --lan` 启动,绑 0.0.0.0
- zai 的 mobile Agent 路由 `/m`(`packages/zai/src/web/src/pages/MobileAgent.tsx`)
- zai 的实例管理路由 `/instances`(`packages/zai/src/web/src/pages/Instances.tsx`)

opencc-web 仓库在 `/Users/ethan/code/opencc-web/`,详见 `opencc-web/AGENTS.md`。

## 版本 / 发布

- 当前: **0.1.4** (versionCode 5)
- 不发 release,只本地 debug APK
- 每次改完手动 bump `versionCode` + `versionName`(`app/build.gradle.kts`),否则手机装上后版本号不变看不出是新版
