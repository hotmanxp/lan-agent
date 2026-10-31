# Android 本地引擎验证记录（2026-10-31）

## 结论
**技术可行性 100% 验证通过。** Node 24 + zai server 在 Android arm64 上跑通了完整链路：
建会话 → 发消息 → 真实模型请求 → 流式回复。

## 验证环境
- 模拟器：`asr_repro`（android-34 google_apis **arm64-v8a**，headless）
- 引擎：opencc-web 的 `zai`（Express 4 + node:http），不是 dsh
- 运行时：`libdshnode.so` / `libnode.so`（Node **v24.22.1** bionic arm64），来自 `jackwener/DSH-Android` 的预编译 release `native-node24.22.1-c5b68634a6`（SHA256 全验过）
- 引擎包布局：`/tmp/engine-pkg/`（见下）

## 引擎包布局（已验证可跑）
```
engine/
├── server.mjs                       # 入口:createApp + http.createServer + listen 127.0.0.1
├── zai-server.mjs                   # esbuild bundle (2.8MB)
└── node_modules/
    ├── @zn-ai/zn-agent-core/{package.json, opencc-core.mjs}   # 必须外置!
    ├── zod/                          # 必须外置 (core 静态 import 108 次 zod/v4)
    └── fflate/                       # core 动态 import
```

## 三个关键约束（都是踩出来的）
1. **`@zn-ai/zn-agent-core` 必须留在 node_modules 里，不能打进单文件。**
   core 用 `import.meta.resolve('@zn-ai/zn-agent-core')` **自引用**
   (`zn-agent-core/src/compat/tools/opencc/builtin.ts:40`、`src/compat/openccInit.ts:49`)
   来注册 opencc 内置工具。bundle 进去 → resolve 不到 → agent 工具全废
   (日志 `[openccBuiltin] cannot resolve @zn-ai/zn-agent-core`)，**但服务照样启动得好好的**
   —— 最难查的那类静默失败。
2. **esbuild banner 注入 `createRequire`**，因为 CJS 依赖
   (express→body-parser→depd) 会调 `require('path')`，ESM bundle 里没 require 就崩
   (`Dynamic require of "path" is not supported`)。
   **标识符不能叫 `__createRequire`** —— core bundle 里已声明同名标识符，
   会报 `Identifier '__createRequire' has already been declared`。用 `__zaiCreateRequire`。
3. **pnpm 的 node_modules 是 symlink**，打包必须 `cp -RL` 解引用，否则断链进 APK 静默失败。

## 环境变量（踩坑）
- `HOME` **必须显式设**：Android 上 `os.homedir()` 读 `$HOME`，不设会退化成 `/`，
  `~/.zai` 写不了，进程直接崩。
- `ZAI_DATA_DIR` 设成 `$HOME/.zai`。
- **`cwd` / `WS` 漏传 = 服务启动即崩**：`initAgentRuntime(opts.cwd)` → `projectDirFor(cwd)`
  收到 undefined → `TypeError: The "path" argument must be of type string` → `createApp` 抛错
  → 进程退出。症状是「LISTENING 之前就死了」，日志里两处报错。
- `AT_SECURE` 在 adb shell 起的进程里是 undefined，**不是** zygote fork 的场景，
  所以 `SafeGetenv` 那些坑（NODE_OPTIONS 被忽略、os.tmpdir() 变 /tmp）**不适用**。
  真正 zygote fork 的 App 进程才会踩（DSH-Android 为此专门做了 preload 猴补丁）。
  → **App 内跑引擎时必须复刻那套 preload**，否则 plugin 整个不激活且无报错。

## 验证结果
| 项 | 结果 |
|---|---|
| `libdshnode.so --version` | `v24.22.1` ✅ |
| 引擎启动 | `LISTENING 9420` ✅ |
| `/api/health` | `{"ok":true,"version":"0.0.0"}` ✅ |
| `/api/agent/sessions` | 正常 ✅ |
| `/api/slash` | 正常 ✅ |
| SSE `/api/event` | 立刻吐 `event: server.connected` ✅ |
| `POST /api/agent/prompt` | 200 ✅ |
| **真实模型请求** | `runtime.delta: "收到"` ✅ (MiniMax-M3.1-Flash) |
| 流式协议 | `runtime.started` / `runtime.delta` / `runtime.done` 齐全 ✅ |
| **BashTool 真执行** | 模型调 BashTool → `echo HELLO_FROM_BASH` → 真跑出 `HELLO_FROM_BASH` ✅ |
| transcript 落盘 | `.zai/projects/<sanitized-cwd>/<sid>.jsonl` ✅ |
| `librg.so` | ripgrep 15.2.0 (+pcre2) 在 Android 上跑通 ✅ |

## 协议确认（lan-agent 现有客户端 100% 复用）
- 建会话：`POST /api/agent/sessions` body `{model, providerId}`（**不传 model 会得到
  `model: unknown` → 运行时回落 → 报 "Not logged in"**）
- 发消息：`POST /api/agent/prompt` body `{sessionId, prompt}`（参数名是 `prompt` 不是 `content`；
  图片才用 `contentBlocks`）
- SSE：`GET /api/event?sid=<sid>`
- 事件类型：`session.created` / `queue.changed` / `session.renamed` / `session/projection` /
  `runtime.started` / `runtime.delta` / `runtime.done` / `runtime.error`

## 剩余阻塞项（Android 真机侧）
1. ~~**bash 缺失**~~ —— **已解决**：在 app 私有 `files/bin/bash` 建一个指向
   `/system/bin/sh` 的 **symlink**，再 `CLAUDE_CODE_SHELL=<that path>`。
   - 为什么要 symlink：`Shell.ts:74 findSuitableShell()` 硬性要求 shell 路径**含
     "bash" 或 "zsh"**（`shellOverride.includes('bash')`），且 `isExecutable` 要过。
     `/system/bin/sh` 两个条件都不满足。
   - `SHELL` env 也走同一套判断（`env_shell.includes('bash')`），所以光设
     `SHELL=/system/bin/sh` 无效。
   - Android 34 的 `/system/bin/sh`（toybox）实测支持 `[[ ]]`、数组、`set -o pipefail`
     —— 能力够 BashTool 用。
   - 兜底还要准备空的 `~/.bashrc`（`ShellSnapshot.ts:181 getConfigFile(shellPath)`
     会去读它）。
2. **ripgrep 无 android 二进制** —— vendor 只有 darwin-arm64/darwin-x64/win32-x64。
   DSH-Android 的 `librg.so`（aarch64, 4.4MB）可以直接拿来用，
   按 DSH-Android 的做法在 `files/bin/rg` 放软链 → `<nativeLibraryDir>/librg.so`，
   并把它挂进 `PATH`。
3. **link(2) 在 Android FUSE 被拒** —— `LocalMainSessionTask.ts:108,418` 的 `/clear` re-link 路径要验
4. **APK 体积** —— 引擎包 14MB + libnode.so 82MB ≈ 100MB 增量
5. **Android 无沙箱** —— agent 全权访问 app 私有目录，靠 `permissions.deny` 白名单兜
6. **API key 明文** —— `~/.zai/settings.json` 的 `env` 是明文，App 侧要用 Keystore 加密

## 已排除的路线
**不跑 dsh（DeepSeek 官方）。** 理由：zai server 是纯 Express + node:http，
`/api/agent/*` + `/api/event` SSE 协议跟 lan-agent 现有 `AgentApi.kt` **完全一致**，
baseUrl 换成 `127.0.0.1` 即可，UI 层一行不用改。dsh 是 Cordis + JSON-RPC over stdio，
协议全不同，UI 要整个重写。
DSH-Android 的价值在于**宿主骨架**（native/ 编译 Node、NodeRuntime JNI、`:engine` 进程、
socketpair + `DSH_BRIDGE_FD`、NDJSON JSON-RPC、ToolDescriptor/ToolResult、三层超时 +
`capability/cancel`）和**手机能力层**（`core/capability/` 67 个工具 / 10 组）。

## App 侧实现（feat/local-engine 分支）
```
app/src/main/java/io/github/hotmanxp/lanagent/engine/
├── EngineService.kt      # :engine 进程的前台服务,起 Node
├── EngineController.kt   # 主进程侧:起引擎 / 盯状态 / 崩了退避重拉
├── EnginePaths.kt        # 目录布局 + 环境变量
├── EngineInstaller.kt    # assets/engine → filesDir 解包(按指纹分目录)
├── EngineShims.kt        # bash / rg 软链
└── EngineStatus.kt       # 跨进程状态文件
app/src/main/java/io/github/jackwener/dsh/engine/NodeRuntime.kt
                          # JNI 入口。**包名必须是这个** ——
                          # libdshengine.so 导出的符号硬编码了
                          # Java_io_github_jackwener_dsh_engine_NodeRuntime_nativeStart,
                          # JNI 按类全限定名查找,找不到就 UnsatisfiedLinkError。
                          # lan-agent 的包名改不了(影响已装用户),所以只能
                          # 建一个同名 Java 包来匹配符号。
tools/
├── build-engine-pkg.mjs  # 构建引擎包(esbuild bundle + 外置依赖 + 自检 + 起服务验证)
├── fetch-native.sh       # 拉预编译的 libnode.so 等(下载 + sha256 强校验)
└── engine-android-env.cjs# AT_SECURE=1 的 env 修复 preload
```

### 环境变量（App 侧喂给引擎进程）
| 变量 | 值 | 为什么 |
|---|---|---|
| HOME | `<filesDir>/home` | os.homedir() 读它,不设会退化成 `/` |
| WS | `<filesDir>/workspace` | 引擎入口的 cwd。**漏传 = 服务启动即崩** |
| ZAI_DATA_DIR | `<filesDir>/home/.zai` | ~/.zai 的等价物 |
| TMPDIR | `<filesDir>/home/tmp` | os.tmpdir() 读它 |
| PORT | 9420 | 引擎监听端口 |
| SHELL / CLAUDE_CODE_SHELL | `<filesDir>/bin/bash` | opencc 的 findSuitableShell() 要求路径含 bash/zsh |
| PATH | `<filesDir>/bin:/system/bin:/system/xbin` | 找 rg 用(`which rg`) |

### 三个必须踩过才知道的坑
1. **JNI 包名**：`libdshengine.so` 的符号硬编码了 `io.github.jackwener.dsh.engine.NodeRuntime`。
   lan-agent 自己的包名是 `io.github.hotmanxp.lanagent`，改不得 → 在 lan-agent 里建一个
   **同名 Java 包** `io.github.jackwener.dsh.engine` 来匹配符号。
2. **bash 软链的名字**：opencc 的 `findSuitableShell()` 硬性要求 shell 路径**含 "bash" 或 "zsh"**。
   Android 只有 `/system/bin/sh`(toybox) → 必须建一个叫 `bash` 的软链指过去。
   Android 34 的 toybox 实测支持 `[[ ]]`、数组、`set -o pipefail`,够 opencc 用。
3. **`defaultConfig { ndk { abiFilters } }` 在没装 NDK 的机器上 Kotlin DSL accessor 不生成**,
   报 "Unresolved reference: ndk"。**不需要写** —— AGP 只打 `src/main/jniLibs/<abi>/` 下
   真实存在的目录,而 `tools/fetch-native.sh` 只放 arm64-v8a 一个。

### AT_SECURE（模拟器实测结论）
DSH-Android 需要 `android-env.cjs` preload,因为它的 Node 是另一种构建方式。
我们这边实测:libnode 是静态编进 `libdshengine.so` 的,**不走 libc 的 SafeGetenv 路径** ——
`AT_SECURE=1` 显式设置后,`process.env` / `os.tmpdir()` / `os.homedir()` 全部正常。
但保险起见还是带了 preload(zai 侧也做了 TZ 修正 —— Android 没有 `/etc/localtime`,
Node 的 ICU 会落到 UTC,时间上下文和预算日切都跟着错)。

---

## APK 内跑通全链路（2026-11-01,0.29.0）

上面第一节的验证是「`adb shell` 手工起引擎」。这次是**引擎在 APK 里**跑,
走 App 自己的启动路径(`LanAgentApp` → `EngineController` → `:engine` 进程 →
`EngineService` → `NodeRuntime.nativeStart`)。

### 验收脚本

`tools/verify-local-engine.sh` —— 22 项全过:

```
1. 原生库     ✓ nativeLibraryDir 定位到 ✓ 5 个 .so 都在 ✓ node 跑起来了:v24.22.1
2. 引擎包     ✓ 引擎目录 / 引擎入口 / core bundle 都在（core 必须外置）
3. 软链       ✓ bash → /system/bin/sh ✓ rg → nativeLibraryDir/librg.so
4. 引擎进程   ✓ App 进程 ✓ :engine 进程 ✓ 端口 9420 在监听
5. HTTP 接口  ✓ /api/health → ok ✓ /api/agent/sessions 有响应 ✓ SSE 吐出 server.connected
```

### UI 全链路（截图取证）

1. **实例列表**里出现「本机引擎」,在线绿标（`resolveAgentInstances()` 把它排第一）;
2. **设置 → 本机引擎**卡片显示「引擎运行中 / http://127.0.0.1:9420」,
   填「服务地址 / 模型名 / API 密钥」→ 保存 → 物化出 `$HOME/.zai.json` +
   `$ZAI_DATA_DIR/settings.json` 两个文件;
3. **切到本机引擎实例** → 新建会话 → 发消息 → **真实模型回复**（`say O` → `O`）;
4. **BashTool 真执行**:「run Bash to execute: echo HELLO_FROM_BASH」→ 模型调 Bash →
   `Output: HELLO_FROM_BASH`,UI 上按 Trae 式渲染成「执行 1 条命令 ›」一行摘要。

### 这一轮挖出来的坑

见 [`local-engine.md` §八](local-engine.md):`extractNativeLibs` 必须开(否则引擎
入口整个不出现)、任务页实例目录不刷新导致「永远离线」、验收脚本的三个判错坑、
重复的旧能力桥实现(旧 `NotificationBridge` 在 manifest 里注册着,系统绑的是它,
而代码读的是新实现 → 通知永远读不到)。
