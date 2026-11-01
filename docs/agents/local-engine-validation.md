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

---

## 改成「按需下载」后的实测（2026-11-01 续）

引擎包与原生库不再打进 APK,改成运行时下载。

| 项 | 结果 |
|---|---|
| APK 体积 | 76MB → **40.8MB**(不含 `libnode.so`、不含 `assets/engine`) |
| 引擎包 zip | 34MB(engine 13MB + native 95MB 压缩后) |
| 下载 + 校验 + 解包 | 局域网约 1.7s(`清单 → 下载 → sha256 通过 → 解到 filesDir`) |
| `System.load(filesDir/native/*.so)` | **一次成功**,无 UnsatisfiedLinkError |
| 引擎启动 | `:engine` 进程 → `LISTENING 127.0.0.1:9420` → HTTP 通了 |
| 凭据保存 → 重启 | `EXTRA_RESTART` → 旧进程自杀 → 控制器重拉 → `凭据物化 = true` |
| 真实模型回复 | ✅ `I'll run that command.` |
| BashTool 真执行 | ✅ `Output: HELLO_FROM_BASH` |
| `tools/verify-local-engine.sh` | **23 项全过**(含「下载源可达」) |

### 这一轮抓到的两个真缺陷

1. **「设置页保存不了 API 信息」** —— 不是 UI 坏了,是**引擎不重启**:引擎进程启动时就把
   `settings.json` / `.zai.json` 读进内存了,改了文件它不知道。用户看到的是「保存了
   但还是用旧凭据」,结论就是「保存不了」。修法:保存后发 `EXTRA_RESTART` 让
   `EngineService` 自杀,`EngineController` 按退避重拉新进程(新进程才重读凭据)。
   另外设置页补了可见反馈(「已保存,引擎重启中…」),不然界面一点变化都没有。
2. **`System.loadLibrary` 用不了** —— 它只认 APK 里的 nativeLibraryDir。改成
   `System.load(绝对路径)`,顺序固定 `libc++_shared` → `libnode` → `libdshengine`:
   后者的 `DT_NEEDED` 是 `libnode.so`(SONAME 同名),靠 linker 按 SONAME 复用
   已加载库解析上,不用改 RPATH。

### 一个 UI 上的小坑(自己踩的)

软键盘弹起时**屏幕下半部分被键盘盖住**,按屏幕坐标 tap 会打到键盘上,不是输入框。
`adb shell input text` 于是把内容打进了上一个字段。**用 TAB 键在字段之间挪焦点**
(`KEYCODE_TAB`)比按坐标 tap 稳得多 —— Compose 的 focus traversal 支持它。

---

## 0.29.2:真机报的 `invalid header value`(密钥里的空格)

真机(不是模拟器)发消息报:

```
API Error: Headers.append: "sk-cp-3CTQOc1rT2UAOCZ8x-fpZSd14wXqzL6F_1y5C3ZaUNWc-4bR7ne6qqlupv9v7bRfEP2ZsBvp dKQHRkJBa9ueENjYpk2Hq8ZRriM1e9 bPMY4Avp3Fhwzf6Es"
is an invalid header value.
```

用户复制 key 时下划线被打成了空格。Node 的 `Headers.append` 拒绝含空白字符的
header 值 → 请求在发出前就炸,而且报错里把**整个 key** 打了出来。

修法:`EngineCredentials.sanitized()` 去掉 `apiKey` 的所有空白字符,
`save()` 与 `apply()` 两道都过(后者洗掉老版本存进去的脏值)。

### 实测(模拟器 asr_repro,0.29.2)

| 项 | 结果 |
|---|---|
| 单元测试 `EngineCredentialsTest` | 4 例全过 |
| 设置页填 `ZW ZW` + 保存 | 提示「已自动去掉 1 个空白字符(密钥里的空格会让请求头非法)」 |
| 物化后的 `$HOME/.zai.json` | `"apiKey":"ABZWZW"` —— **空格没了** |
| 引擎重启 | `凭据物化 = true` → `LISTENING 127.0.0.1:9420` |
| 真实模型回复 | `runtime.delta` → `O` ✅(无 header 报错) |
| `tools/verify-local-engine.sh` | 22 项全过(未传 `MANIFEST_URL`,带下载源可达性共 23 项) |

### 顺带修的 UI bug:凭据输入框不回填已存的值

原来写的是 `remember { mutableStateOf(stored.x) }`。DataStore 的 flow 是异步的,
首帧拿到的还是默认空值,`remember` 把那个空值**永远**留住 —— 用户打开设置页
看到的是三个空框,以为凭据没存上;重填一遍还会把已存的冲掉(实测把 baseUrl /
model 存成了空)。改成 `remember(stored.x)`,flow 一发值就回填,保存后回填的是
去空白的干净值。

---

## 0.29.3:真机上 Grep / Glob 工具不可用(SELinux 拒绝 exec app_data_file)

真机现象:引擎起来、BashTool 正常,但 **Grep / Glob 全废**,报
`spawn rg EACCES`。AVC 日志里两条 deny:

```
deny { execute }        untrusted_app → app_data_file(自己数据目录里的文件)
deny { execute_no_trans } untrusted_app
```

**根因**:原生库改成按需下载后,`librg.so` 解到 `filesDir/native/`
(`app_data_file` 标签),软链 `files/bin/rg` 指过去。部分 Android 版本
(Android 15/16)的策略禁止 `untrusted_app` 执行自己数据目录里的文件。

**迷惑性**:BashTool 照常 —— 它 exec 的是 `/system/bin/sh`(系统分区);
引擎照常起 —— Node 运行时是 `dlopen`(不需要 exec 权限)。
只有「exec 自己目录里的二进制」这一类工具废掉。**模拟器(Android 14)完全
测不出来**,那边策略宽松。

### 修法

1. `librg.so` 回 APK 的 `app/src/main/jniLibs/arm64-v8a/`(4.4MB,已入库);
2. `packaging { jniLibs { useLegacyPackaging = true } }` —— 让它解到
   `nativeLibraryDir`(`/data/app/<pkg>/lib/arm64`,`apk_data_file` 标签,
   平台自己就执行那里的 .so);
3. `EngineShims` 优先软链 `applicationInfo.nativeLibraryDir/librg.so`,
   拿不到再退回下载的那份。

95MB 的 Node 运行时**仍然不进 APK**。

### 实测(模拟器 asr_repro,0.29.3)

| 项 | 结果 |
|---|---|
| APK 体积 | 39.5MB(0.29.2 是 40.9MB) |
| `nativeLibraryDir/arm64/librg.so` | 在,`-rwxr-xr-x`,标签 `u:object_r:apk_data_file` |
| 软链 | `files/bin/rg → /data/app/.../lib/arm64/librg.so` |
| `files/bin/rg --version` | `ripgrep 15.2.0 (rev e89fff89ac)` ✅ |
| **Grep 工具真跑** | SSE 里 `toolName: "Grep"` → 命中 `sample.txt:1` ✅ |
| `tools/verify-local-engine.sh` | 22 项全过(含新增的「rg 能执行」) |

### 验收脚本的教训

软链**存在**不等于**能执行**。`verify-local-engine.sh` 第 3 步原来只看
`readlink` 的结果 —— 在真机上这条照样通过,而工具是废的。现在改成真的跑一次
`files/bin/rg --version`,跑不出来就报 fail。

### 一条要更正的判断

上面写「模拟器(Android 14)测不出来」**不准确**。后来在模拟器上用 BashTool
直接 exec 私有目录里的文件,拿到的是同一个 `Permission denied`(exit 126)——
**SELinux 只给了 `untrusted_app` 对 app_data_file 的 `execute`(dlopen/mmap
PROT_EXEC 要的),没给 execve 要的 `execute_no_trans`**。所以这是普遍行为,
不是「某些 Android 版本才严格」。

`run-as` 手工跑能成功,是因为 run-as 跑在 `runas_app` 域,权限比 untrusted_app 宽。

---

## 0.29.4:让 agent 能在 Bash 里用 node

需求:agent 在 BashTool 里能跑 `node script.js` / `node -e '…'`。

### 三件事

1. **启动器进 APK**:`libdshnode.so`(4.6KB,node 启动器)放
   `app/src/main/jniLibs/arm64-v8a/` → 解到 `nativeLibraryDir`。
   **必须进 APK** —— 私有目录里的文件 exec 不了(见上面那条 execute_no_trans)。
   `libnode.so` 本体(82MB)仍在下载目录。
2. **`files/bin/node` 软链** → `nativeLibraryDir/libdshnode.so`。
   PATH 里已有 `files/bin`,`which node` 找得到。
3. **引擎 env 加 `LD_LIBRARY_PATH=<filesDir>/native`**:启动器的 RPATH 只有
   `$ORIGIN`(nativeLibraryDir,那儿没有 libnode.so),DT_NEEDED 是
   `libnode.so` / `libc++_shared.so`。
   另加 `NODE_PATH=<引擎目录>/node_modules`,让脚本能 require 引擎自带的依赖。

### 为什么 LD_LIBRARY_PATH 对引擎自己没用、对子进程却有用

引擎进程是 zygote fork 来的,`AT_SECURE=1` → 动态链接器忽略它自己 env 里的
`LD_LIBRARY_PATH`(所以引擎内的 Node 靠 `android-env.cjs` preload 猴补丁修 env)。
引擎 **exec 出去的子进程**(bash、node)是重新 execve 的,auxv 由内核重新算,
`AT_SECURE=0` → `LD_LIBRARY_PATH` 正常生效。

### 顺带修:App 升级后软链悬空

`nativeLibraryDir` 路径带随机目录名(`/data/app/~~XXXX/pkg-YYY/lib/arm64`),
App 升级后路径就变了,旧的 `files/bin/rg` / `files/bin/node` 全变悬空软链。
`File.exists()` 对悬空软链返回 **false** → 判「软链不存在」→ 直接
`Os.symlink()` → 撞 `EEXIST` 失败 → 软链永远指着旧路径。
**判存在改用 `Os.readlink()`**(悬空软链照样返回它指向的字符串)。

实测:升级 APK 后 rg 软链报 `symlink failed: EEXIST`,rg 指向旧的
`/data/app/~~aoDIhXts…`(上一版的路径)。

### 实测(模拟器 asr_repro,0.29.4)

| 项 | 结果 |
|---|---|
| `files/bin/node` 软链 | → `nativeLibraryDir/libdshnode.so` ✅ |
| `node -e 'console.log(process.version)'` | `v24.22.1` ✅ |
| `node -e 'require.resolve("zod")'` | 引擎的 `node_modules/zod/index.cjs` ✅ |
| `node script.js` | `from script 42` ✅ |
| `process.cwd()` | `files/workspace`(agent 工作区)✅ |
| node 写文件 | ✅ |
| `tools/verify-local-engine.sh` | **24 项全过**(新增「rg 能执行」「node 能执行」) |



---

## 0.29.5:手机端专用主 Agent

参考 core 的 `mainAgents-weixin.ts`,给手机做一份专用主 Agent:提示词讲清
「你在 Android 手机上跑」,工具池砍掉依赖电脑的那些。

### 形态:外置 main-agent(不是改 core)

`app/src/main/assets/local-engine/mobile-main-agent.js` →
`EngineAgentProfile.apply()` 每次起引擎拷到
`$ZAI_DATA_DIR/main-agents/mobile.js`,并把 `mainAgent: "mobile"` 合并进
`settings.json`(读改写 —— 同一文件里还有 `env` 和 `permissions`)。

引擎启动时 `initAgentRuntime` 扫那个目录加载外置 agent(core
`server/agentRegistry.ts`),`settings.mainAgent` 让新会话选中它。

### 实测(模拟器 asr_repro)

| 项 | 结果 |
|---|---|
| 物化 | `files/home/.zai/main-agents/mobile.js` ✅ |
| settings.json | `{"env":…,"permissions":{"defaultMode":"bypassPermissions"},"mainAgent":"mobile"}` ✅ |
| agent 自述环境 | 「Android 手机上,应用包名 io.github.hotmanxp.lanagent,引擎是应用内的 Node.js 进程,仅能读写该应用的私有沙箱目录」 ✅ |
| agent 自述工作目录 | `/data/data/io.github.hotmanxp.lanagent/files/workspace` ✅ |
| agent 自述 node | v24.22.1,仅核心模块 ✅ |
| **工具池(保留)** | Agent, AskUserQuestion, Bash, Edit, Glob, Grep, **PresentFile**, Read, Skill, TaskStop, TodoWrite, WebSearch, Write, `mcp__minimax-image__generate`, `mcp__phone-bridge__phone_{clipboard,list_apps,notifications,open_app,screenshot,settings}`, subagent_control |
| **工具池(砍掉)** | CliAgent, CronCreate, CronDelete, CronList, EnterPlanMode, EnterWorktree, ExitPlanMode, ExitWorktree, Monitor, snip |
| 单测 `EngineAgentProfileTest` | 4 例全过 |

引擎日志里有一行 `[mobile-agent] tool pool = …` —— 排查工具问题时先看它。

### 踩到的两件事

1. **`require('@zn-ai/zn-agent-core')` 在外置 agent 里解析失败** —— 同一个
   NODE_PATH 在普通 node 子进程里是好的(`require.resolve` 能拿到路径),
   但在「$ZAI_DATA_DIR 里由 `import()` 加载的 CJS 文件」这个上下文里报
   `Cannot find module`。改成**按文件 URL 动态 import**(引擎目录从
   `OPENCC_MODS_DIR` 反推)才通。
2. **`PresentFile` 只由 core 的 `default` agent 挂** —— core 没导出那个工具对象,
   外置 agent 拿不到。所以手机 agent 的 tools 槽先沿用 default 的槽(拿到
   PresentFile),再在上面做黑名单过滤。

### 顺带修:settings.json 的 permissions 段必须无条件写

原来 `EngineCredentialsStore.apply()` 在凭据没配全时直接 return,连
settings.json 都不写 —— 而 zai 启动时看到文件里没有 `permissions` 会**回填一份
桌面默认(询问模式)**,手机上没有批准界面 → 每个工具调用都卡住。现在
settings.json 总是写(env 只在配全时写)。
