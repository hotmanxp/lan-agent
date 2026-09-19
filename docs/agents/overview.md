# lan-agent 项目结构概览

> **面向 agent 的事实型索引**: 本文件只列"看一眼代码就能得到"的信息(版本号、目录结构、路由清单)。设计决策见根 `AGENTS.md`,排障经验见 `docs/agents/pitfalls.md`。

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| 语言 | Kotlin | 2.0.21 |
| 构建 | Gradle / AGP | 8.10 / 8.6.1 |
| UI | Jetpack Compose (BOM) | 2024.10.00 |
| 导航 | Navigation Compose | 2.8.4 |
| 持久化 | DataStore Preferences | 1.1.1 |
| 序列化 | kotlinx-serialization-json | 1.7.3 |
| Markdown | **自研**(`ui/Markdown.kt`) | — |
| WebView | AndroidX Webkit | 1.12.1 |
| 相机 | CameraX (core/camera2/lifecycle/view) | 1.3.4 |
| 扫码 | ML Kit Barcode Scanning | 17.3.0 |
| HTTP | OkHttp | 4.12.0 |
| SSH | JSch | 0.1.55 |
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
├── AGENTS.md                          # ← 设计决策 + 索引
├── docs/
│   ├── agents/
│   │   ├── overview.md                # ← 本文件
│   │   └── pitfalls.md                # 已知坑 / 排障经验
│   └── superpowers/                   # spec / plan 历史档案
│       ├── specs/
│       │   ├── 2026-08-24-lan-agent-android-app-design.md
│       │   └── 2026-09-14-workbuddy-api-token-applicability.md
│       └── plans/
│           └── 2026-08-24-lan-agent-android-app.md
└── app/
    ├── build.gradle.kts              # compileSdk 34 / minSdk 26
    ├── proguard-rules.pro             # 空(debug-only)
    ├── src/test/                      # JVM 单测基建(0.9.1 起)
    └── src/main/
        ├── AndroidManifest.xml        # 单 Activity + 6 类权限 + service
        ├── res/
        │   ├── values/{strings,themes,colors}.xml
        │   ├── values-night/{themes,colors}.xml          # 深色冷启动底色 #141517
        │   ├── xml/network_security_config.xml   # base-config cleartextTrafficPermitted="true"
        │   ├── mipmap-anydpi-v26/                 # 自适应图标(青绿底 + 机器人头前景)
        │   ├── mipmap-{hdpi,mdpi,xhdpi,xxhdpi,xxxhdpi}/
        │   └── drawable-nodpi/
        │       ├── wb_mascot.png                 # WorkBuddy 机器人形象
        │       └── ic_launcher_foreground.png    # 自适应前景(108dp 画布,66dp 安全区)
        └── java/io/github/hotmanxp/lanagent/
            ├── MainActivity.kt        # setContent + immersive + 媒体权限申请
            ├── LanAgentApp.kt         # Application;注册 WebViewKeepAlive 通知 channel
            ├── ui/                    # 屏幕 + 渲染组件
            ├── data/                  # 状态机 + DataStore + wire 模型 + API 客户端
            ├── model/                 # @Serializable 数据类
            ├── ssh/                   # JSch 封装 + zai 启动 + 端口探测
            ├── service/               # WebView 配置单源 + 后台保活 service
            └── voice/                 # 语音输入(平台 SpeechRecognizer + 云 ASR)
```

## AppNavHost 路由清单

```
tab/{tasks,instances,ssh,services,settings}        # 底栏 5 栏根
  + scan                                            # QR 扫码
  + webview/{url}                                   # WebView 详情
  + agent-sessions/{baseUrl}/{instanceName}         # 原生会话列表
  + agent-session/{baseUrl}/{instanceName}/{sid}    # 原生会话详情
  + ssh-terminal/{hostId}                           # SSH 终端
```

baseUrl / instanceName / hostId 都要 `Uri.encode`(路径里含 `://`、中文等)。

## 底栏 5 栏路由

| # | 栏 | 路由 | 内容 |
|---|----|------|------|
| 1 | **任务** | `tab/tasks` | **原生 Agent 工作区**(0.15.0):当前实例 + 当前会话 + 会话切换面板 |
| 2 | **实例** | `tab/instances` | `InstancesScreen`(原生实例管理,2.5s 轮询) |
| 3 | **SSH** | `tab/ssh` | `SshHostListScreen`(主机列表 + 终端 + 快捷命令) |
| 4 | **服务** | `tab/services` | `RemoteServicesScreen`(局域网服务 + 探活) |
| 5 | **设置** | `tab/settings` | `SettingsScreen`(主题 / 入口卡片 / 数据概览 / 关于) |

显示名与图标统一从 `TabDestination` 枚举(`ui/BottomTabs.kt`)读,单一事实来源。
