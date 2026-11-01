# 本地引擎(feat/local-engine)

> 把 agent 引擎塞进 APK,在手机上本地跑。
> 引擎 = opencc-web 里的 `zai` server(Express 4 + `node:http`,**不是** Bun)。
>
> 状态:P0(引擎包构建脚本 + JVM 单测)已完成并跑通;P1(Kotlin 侧引擎进程)
> 已到「APK 打出来 + 模拟器上引擎 HTTP 通了 + 现有会话 UI 能当普通实例
> 用」;手机能力通道(P2)只留了接口,没实现。

---

## 一、架构

```
┌─────────────────────────────────────────────────────────┐
│ 主进程 (io.github.hotmanxp.lanagent)                     │
│   OkHttp → http://127.0.0.1:9420/api/agent/* + /api/event │
│   ↑ 复用现有的 AgentApi / SSE 客户端,一行都不用改           │
└──────────────────────────┬──────────────────────────────┘
                           │ 同一个设备共享同一个 loopback
┌──────────────────────────┴──────────────────────────────┐
│ :engine 进程 (android:process=":engine")                 │
│   libnode.so (Node 24) + zai-server.mjs → HTTP 服务      │
│   监听 127.0.0.1:9420                                    │
└─────────────────────────────────────────────────────────┘
```

三个设计要点:

1. **引擎是普通 HTTP 服务**,协议跟局域网那个实例一模一样。App 侧只是
   「多一种 baseUrl 来源」——`AgentApi.kt` / SSE 客户端一行都不用改。
2. **必须独立进程**。`node::Start` 一个进程只能调一次(V8 / Node 的全局状态
   不可重入),引擎崩了没法原地重启,只能整个进程换新的。所以服务声明
   `android:process=":engine"`,Node 退出后杀进程,由主进程按退避重新拉。
3. **只监听 127.0.0.1**。引擎没有任何鉴权(`token: 'local'` 是摆设),
   暴露到局域网等于把 agent 的 bash 权限敞开 —— 别改这个 host。

---

## 二、引擎包布局(为什么这么摆)

产物在 `app/src/main/assets/engine/`(gitignore,构建产物不入库):

```
engine/
├── server.mjs                       引擎入口:起 zai 的 HTTP 服务
├── zai-server.mjs                   esbuild 打包出来的 zai server(2.8MB)
├── engine.sha256                    整棵树的指纹,App 侧用它决定要不要重解
└── node_modules/
    ├── @zn-ai/zn-agent-core/        ← **必须外置**
    │   ├── package.json
    │   └── opencc-core.mjs          (7.2MB,从 packages/zn-agent-core/dist 原样拷)
    ├── zod/                         core 的静态第三方依赖
    └── fflate/                      core 的动态 import 依赖
```

每条约束背后都有一个「装上了但引擎起不来 / 起得来但工具全废」的坑:

| 约束 | 不这么做会怎样 |
|------|----------------|
| `@zn-ai/zn-agent-core` **必须留在 node_modules 外** | core 用 `import.meta.resolve('@zn-ai/zn-agent-core')` **自引用**找回自己(`packages/zn-agent-core/src/compat/tools/opencc/builtin.ts:40`、`src/compat/openccInit.ts:49`)。bundle 进去就 resolve 不到 → **agent 工具全废**,报 `[openccBuiltin] cannot resolve @zn-ai/zn-agent-core`,而服务启动得好好的 —— 最难查的一类静默失败 |
| `zod` / `zod/v3` / `zod/v4` 外置 | core 静态 import 了 **108 次** `zod/v4`。内联进 bundle 的话 core 副本和 zai server 副本会各带一份 zod,类型实例对不上 |
| `sharp` / `node-pty` 外置 | 手机上没有原生库。外置之后 node-pty 是 lazy require + try/catch → 顶多 503;sharp 只在图片处理路径动态 import,都不影响启动 |
| esbuild banner 注入 `createRequire` | CJS 依赖(express → body-parser → depd)会 `require('path')`,ESM bundle 里没 require 就崩:`Dynamic require of "path" is not supported` |
| banner 的标识符叫 `__zaiCreateRequire` | **不能**叫 `__createRequire` —— core bundle 里已经声明了同名标识符,报 `Identifier '__createRequire' has already been declared` |
| pnpm 的 node_modules 要 `cp -RL` 解引用 | pnpm store 全是软链,不解引用打进 APK 就是断链,运行时才炸,日志里只有一句 ENOENT |
| 不打包 `dist/web` | 前端 SPA,几 MB,纯浪费 |

---

## 三、构建

### 3.1 引擎包

```bash
node tools/build-engine-pkg.mjs            # 构建 + 自检 + 真起一遍验
node tools/build-engine-pkg.mjs --skip-verify
node tools/build-engine-pkg.mjs --out <dir> # 换输出目录
```

- 源仓库默认 `/Users/ethan/code/opencc-web`,可用 `OPENCC_WEB_ROOT` 或
  `--web-root` 换。
- esbuild 用 opencc-web pnpm store 里的那份(钉死 `0.28.1`),不另外装。
- 脚本**幂等**:每次先 `rm -rf` 输出目录再重建,不会残留上次的垃圾文件。
- 脚本**失败很响**:源文件缺失、esbuild 找不到、产物结构不对、起服务后
  `/api/health` 不通 —— 任何一条不过都直接退出非 0,并把 stdout / stderr 尾部
  打出来。引擎包坏了却在手机上才炸是最糟的情况。
- 跑完会自动验一遍:起 `node server.mjs`,curl `/api/health` 期望
  `{"ok":true,...}`,读 `/api/event` 期望第一个事件是 `server.connected`。

产物里 `zod` 的 `.d.ts` 和 `src/`(TS 源码)会被砍掉 —— 运行时走不到
(`@zod/source` 是自定义 export condition,Node 不设),砍掉省 3.3MB 和 400 个
文件。砍完的风险由「真跑一遍」兜底。

### 3.2 原生 .so

```bash
tools/fetch-native.sh                          # 从 GitHub release 下载
tools/fetch-native.sh --from /path/to/arm64-v8a # 从本地目录拷(离线 / 网络不通)
tools/fetch-native.sh --download --from <dir>   # 先试下载,失败退本地
```

装到 `app/src/main/jniLibs/arm64-v8a/`(gitignore)。AGP 默认就吃这个目录,
不用额外配 `sourceSets`。

**ABI 只留 arm64-v8a** —— 靠 `build.gradle.kts` 里的
`packaging { jniLibs { excludes += setOf("**/armeabi-v7a/**", "**/x86/**", "**/x86_64/**") } }`:

- libnode.so 82MB,别的 ABI 加进来 APK 直接翻倍;
- 更要命的是**那些架构上没有 libnode.so** —— 依赖树里带着 armeabi-v7a /
  x86 / x86_64 的 .so(termux terminal-view、barhopper、oniguruma 等),
  不过滤的话非 arm64 设备上 `System.loadLibrary("node")` 直接
  `UnsatisfiedLinkError` **崩掉**,不是降级。

为什么不用 `defaultConfig { ndk { abiFilters } }`:两者在 AGP 8.7.3 都合法
(`ndk` accessor 在 `VariantDimension` 上,`abiFilters` 是 `Set<String>`),
但语义不同 —— `abiFilters` 是「只打包这些 ABI」,一个都匹配不上时打包
直接失败;`excludes` 是「排掉这几个目录」,排完还剩什么都不管。这里的 .so
来源杂(依赖树带的 + 预编译件),用 `excludes` 更不容易踩构建期硬失败。

> 别被「没装 NDK 就报 Unresolved reference: ndk」骗了 —— 那个错的真正原因
> 是把 `ndk { }` 写在 `android { }` 顶层;它只认 `defaultConfig` 里的位置。
> 跟构建机装没装 NDK 无关(我们只用预编译 .so,不本地编译)。

二进制来自上游 `jackwener/DSH-Android` 的预编译 release(它自己也是从 Node
24.22.1 编的),release tag 由「所有原生输入」的哈希推导:

```
tag = native-node<NODE_VERSION>-<sha256(build.sh + patches + engine-jni + dshnode)[:10]>
```

脚本里钉死了 `native-node24.22.1-c5b68634a6` 和每个 .so 的 sha256 —— 换版本
时先更新校验和,否则脚本直接拒绝(宁可装不上,也不能装个错的 .so 进 APK)。

> 踩坑:这台机器上从 GitHub release 下载走 HTTP/2 会
> `PROTOCOL_ERROR (curl 92)`,得加 `--http1.1`。脚本里已经加上了。
>
> 另一个坑:**直连 GitHub release 只有 ~60KB/s**(31MB 要 3 分钟),
> `gh-proxy.com` 实测 2.9MB/s。脚本按「直连 → gh-proxy」顺序试,
> 完整性不靠传输通道保证 —— 靠上面钉死的 sha256,镜像换来的字节流
> 校验不过照样拒绝。

---

## 四、Kotlin 侧怎么起

| 文件 | 职责 |
|------|------|
| `io/github/jackwener/dsh/engine/NodeRuntime.kt` | JNI 绑定 |
| `io/github/hotmanxp/lanagent/engine/EnginePaths.kt` | 目录布局 + 环境变量 |
| `io/github/hotmanxp/lanagent/engine/EngineInstaller.kt` | assets → filesDir 解包(按指纹分目录) |
| `io/github/hotmanxp/lanagent/engine/EngineShims.kt` | 建 `files/bin/{bash,rg}` 软链 |
| `io/github/hotmanxp/lanagent/engine/EngineService.kt` | `:engine` 前台服务,起 Node |
| `io/github/hotmanxp/lanagent/engine/EngineStatus.kt` | 跨进程状态文件 |
| `io/github/hotmanxp/lanagent/engine/EngineController.kt` | 主进程侧:起停 + 退避重启 |

### 4.1 `NodeRuntime` 的包名不能改 ⚠️

`NodeRuntime` 在 `io.github.jackwener.dsh.engine`,**不是** lan-agent 自己的
`io.github.hotmanxp.lanagent`。原因:

`libdshengine.so` 导出的 JNI 符号是硬编码的

```
Java_io_github_jackwener_dsh_engine_NodeRuntime_nativeStart
```

JNI 查符号用的是**类的全限定名**(把 `.` 换成 `_`)。lan-agent 的包名改不了
(会影响已装用户),所以只能在这个 app 里开一个**同名 Java 包**来对上符号。
「顺手改成 lanagent」会让 `nativeStart` 直接 `UnsatisfiedLinkError`,
而且报错发生在 `System.loadLibrary` 那一刻,现场什么日志都没有。

### 4.2 环境变量必须显式喂

Android 上 Node 的三个「默认路径」全是坑:

| 变量 | 不设会怎样 |
|------|-----------|
| `HOME` | `os.homedir()` 读 `$HOME`,不设会退化成 `/`,`~/.zai` 写到根目录 → 非 root 直接 EACCES |
| `TMPDIR` | `os.tmpdir()` 默认 `/tmp`,不可写 |
| `ZAI_DATA_DIR` | zai 的数据目录,默认 `$HOME/.zai` |

`EnginePaths.env()` 一次性给全,`server.mjs` 里再 `mkdirSync` 一遍兜底。

另外还有两条 Android 专属的:

- `WS` **漏传 = 服务启动即崩**:`initAgentRuntime(opts.cwd)` 收到 undefined,
  `projectDirFor(cwd)` 抛 "The path argument must be of type string",
  `createApp` 直接把进程带走。
- `SHELL` / `CLAUDE_CODE_SHELL` / `PATH`:opencc 的 `findSuitableShell()` 硬性
  要求 shell 路径**含 "bash" 或 "zsh"**,Android 的 `/system/bin/sh`(toybox)
  名字里没有 bash → 直接被拒,抛 "No suitable shell found",**BashTool 全废**。
  解法不是把 bash 打进 APK,而是建一个叫 `bash` 的软链指过去
  (`EngineShims`)。`rg` 同理:opencc 的 `resolveRgSystem()` 走 `which rg`,
  所以把 APK 里的 `librg.so` 软链成 `rg` 放进 PATH 就行。

### 4.3 AT_SECURE:App 进程里的 env 会被 Node 拒

App 进程是 zygote fork 出来的,带 `AT_SECURE=1` → Node 认为进程是特权的,
原生 `SafeGetenv()` 拒绝一切 env 查找。`process.env` 还在(它是初始 environ
的快照),但 `os.tmpdir()` 这类实时查表全废,退回到不存在的 `/tmp`。

`tools/engine-android-env.cjs` 这个 preload 在 zai 加载之前把
`TMPDIR` / `NODE_COMPILE_CACHE` / `NODE_EXTRA_CA_CERTS` 恢复回来。启动参数
是 `--require <engineDir>/android-env.cjs`。

**没有它的症状**:引擎能起、SSE 能通,但凡是碰临时目录的功能静默失败 ——
最难查的那一类。

### 4.4 解包按指纹分目录

`engine.sha256` 是构建脚本写的整树指纹。App 侧解到
`filesDir/engine/<指纹前 16 位>/`,指纹变了自动换新目录、旧的删掉。
原地覆盖的话,升级解到一半被杀会留下「一半新一半旧」的目录。

---

## 五、踩过的坑

1. **`import.meta.resolve` 自引用** —— core 必须外置,否则 agent 工具全废
   而服务照常启动。见 §二。
2. **`__createRequire` 撞名** —— core bundle 里已经有这个标识符,banner 里的
   必须带前缀。见 §二。
3. **pnpm 软链** —— 拷进 APK 前必须解引用。见 §二。
4. **`HOME` 不设 = `/`** —— Android 上 `~/.zai` 写不了,报错还发生在 agent
   第一次写配置的时候,现场极难查。见 §4.2。
5. **Node 只能起一次** —— 崩了必须换进程,不能原地重启。见 §一。
6. **`undici` 的 keep-alive 连接池会让 node 脚本跑完不退出** —— 看起来像
   卡死。构建脚本里改用 `node:http` + `agent: false`,并在最后显式 flush +
   exit。
7. **`fs.cp` 要 `dereference: true`** —— Node 的 `fs.cp` 默认**跟着软链拷目标
   内容**(等价 `cp -rL`)还是**拷软链本身**跟平台有关;显式写 `dereference:
   true` 才不靠运气。
8. **GitHub release 下载的 HTTP/2 PROTOCOL_ERROR** —— 加 `--http1.1`。
9. **`ndk { }` 在 `android { }` 下不认** —— AGP 的 Kotlin DSL 里 `ndk` 是
   `defaultConfig` 的属性,不是 `android` 的。
10. **`extractNativeLibs` 必须开** ⚠️ —— 见下面「0.29.0 落地时挖出来的坑」第 1 条。
11. **任务页的实例目录**只解析一次** —— 没有 2.5s 轮询(那是实例管理屏才有的),
    所以「首帧探活必然离线」会一直离线下去。见「0.29.0 落地时挖出来的坑」第 2 条。

---

## 六、还没做的

- **`node-pty` / `sharp`**:手机上缺原生库,当前路径下分别表现为 503 和
  图片处理不可用。要支持得把 `libdsh_pty.so` 装到
  `node_modules/node-pty/build/Release/pty.node`。
- **API key 明文**:`filesDir/home/.zai.json` 里的 `apiKey` 是明文(引擎认这个
  位置)。要加密得改引擎侧的取值链,App 侧加密只能变成「引擎解不开」——
  除非把密钥放进引擎包 / Keystore 派生,那是另一件事。
- **真机验证**:目前只在模拟器(arm64)上跑通。真实手机上有前台服务
  限制、厂商省电策略等差异,得实测。
- **截屏授权的 UI 入口**:`ScreenCaptureService` 已有,但「授权截屏」那个按钮
  还没接进首页 —— 能力调用会返回「去开」的提示,用户得自己找入口。

## 七、UI 是怎么接上的(零改动)

`resolveAgentInstances()` 里把本地引擎当成**第一个实例**插进列表:

```kotlin
val local = localEngineInstance()
return listOfNotNull(local) + snapshots.toAgentInstances(manager)
```

`localEngineInstance()` 做三件事:

1. `EngineInstaller.isInstalled()` 为 false(没跑 `fetch-native.sh` /
   引擎包没进 APK)就返回 null —— 与其给一个永远连不上的入口,不如不出现;
2. `EngineController.start()` 顺手把引擎拉起来(幂等);
3. 用**现有的** `AgentApi` 探一次 `/api/agent/sessions` 判 online。

探活超时给的是 8s,不是卡片路径的 1.5s(后者会把「正在启动」误判成「离线」)。

---

## 八、0.29.0 落地时挖出来的坑(APK 内跑通全链路)

### 1. `extractNativeLibs` 必须开 ⚠️ 最隐蔽的一个

AGP 默认的 **modern packaging**(`extractNativeLibs=false`)把 .so 压缩在 APK 里,
**不落到 `nativeLibraryDir`**。后果是两条独立的坏法:

- `EngineInstaller.isInstalled()` 查 `File(nativeLibraryDir, "libnode.so").exists()`
  → **永远 false** → 引擎入口整个不出现(设置页显示「这个 APK 里没带引擎包」),
  而且**没有任何报错**;
- `EngineShims` 建 `rg` 软链指向 `nativeLibraryDir/librg.so` → 目标文件不存在
  → Grep / Glob 工具全废。

JNI 加载(`System.loadLibrary`)两种模式都能用,但引擎需要**磁盘上真实存在的
.so 文件**做软链目标 —— 所以必须开:

```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = true   // = extractNativeLibs=true
    }
}
```

**症状对照**:App 起来了、`:engine` 进程起来了,但 `files/engine/` 目录压根不存在
→ 说明 `isInstalled()` 判 false,引擎压根没被拉起来。

### 2. 任务页的实例目录不刷新 → 「本机引擎」永远离线

`resolveAgentInstances()` 在任务页只在 bootstrap 时跑一次(`LaunchedEffect(bootstrapTick)`),
**没有轮询**(2.5s 轮询是实例管理屏 `InstancesScreen` 才有的)。

而引擎冷启动要解包 12MB + 起 V8,**好几秒** —— 首帧探活必然是「离线」。
不补一轮的话,「本机引擎」在实例列表里会一直显示离线,用户点了也连不上。

补法(见 `ui/AgentSessionScreen.kt`):一个只探 `127.0.0.1` 的轻量轮询,
通了就把那一行翻成在线。**不能重扫局域网实例** —— `/api/instances` 或逐卡片探活
太贵,2 秒一次会把整屏拖垮。

⚠️ 写这个 effect 时踩过一个逻辑坑:第一版写成
`if (instances.firstOrNull { … }?.online != false) return` —— 目录解析是**另一个
coroutine**,effect 启动时 `instances` 还是空列表 → `firstOrNull` 返回 null →
`null != false` → 直接 return,轮询根本没开始。**空 ≠ 已在线**,null 要当「还没出现」
继续等。

### 3. 验收脚本本身的三个坑(tools/verify-local-engine.sh)

写验收脚本时踩的,改脚本前先看:

- `adb shell "run-as pkg ls -d files/engine/*/"` 里的通配符是**外层设备 shell**
  展开的(相对 `/`),不是 `run-as` 里展开的 → 永远匹配不到。必须写绝对路径
  `/data/user/0/<pkg>/...`;
- **判成败要看退出码,不能看输出**:`test -f` / `grep -q` 成功时一个字都不打印,
  外面再 `grep -q .` 就永远失败;
- `run-as` 后面跟的必须是**真实存在的可执行文件**:`test` 是 shell 内建,得写成
  `run-as pkg sh -c "test -f …"`;
- 拿 `nativeLibraryDir` 只能问 `dumpsys package`(`/data/app` 对 shell 不可读),
  而且解析时用 `[^=]*` 而不是 `.*` —— 路径里带 `==`(随机目录名),贪婪匹配会把
  路径吃成 `/lib`。

### 4. 重复实现要清掉

能力桥这块一度有两套并行实现:`ScreenshotHolder` + `NotificationBridge`(旧)与
`ScreenCaptureService` + `PhoneNotificationListener`(新)。旧的两份没被任何地方
调用,但 `NotificationBridge$Listener` **在 manifest 里注册着** —— 两个
`NotificationListenerService` 并存,系统绑的是旧的那个,而能力代码读的是新的,
表现是「通知永远读不到」。

已删掉旧的两份(`git` 里没入库,备份在 `/tmp/lan-agent-deadcode/`),manifest 改成
注册 `PhoneNotificationListener`。


---

## 九、按需下载(0.29.0 续)

引擎包(13MB)与原生库(95MB)**不再打进 APK** —— APK 只留纯客户端。
用户在设置页点「下载引擎」,App 从一个 manifest 地址拉一个 zip 下来。

### 为什么这么改

内嵌版 APK 是 76MB,分享和安装都重,而且改一行 App 代码就要重新打这个大包。
下载版 APK 40.8MB(剩下的体积主要是 dex —— AA 迁移带来的,跟引擎无关)。

### 产物长什么样

```
dist/                            ← gitignore 掉,构建产物都在这
├── engine/                      tools/build-engine-pkg.mjs 产出
├── natives/arm64-v8a/           tools/fetch-native.sh 拉下来
└── engine-bundle/
    ├── engine-manifest.json     {version, fingerprint, file, sha256, size}
    └── engine-arm64-v8a-<sha12>.zip   34MB
        ├── engine/              引擎包
        └── native/              7 个 .so
```

- `tools/pack-engine-pkg` → `tools/pack-engine-bundle.mjs`:校验引擎包结构 → 打 zip
  → 写 manifest(sha256 + size)
- `tools/serve-engine.sh`:静态服务 `dist/engine-bundle/`,打印 App 该填的地址

### App 侧的三个关口(一道都不能省)

1. **sha256 校验** —— 传输层不可信(局域网也可能被投毒 / 传一半损坏)。
   校验不过就删掉重下,绝不解包
2. **解到 staging 再切换** —— 解一半被杀是最难查的坏法(「一半新一半旧」)。
   先解到 `.bundle-staging-<指纹>/`,全解完、全校验过,再 rename 到位
3. **解包防目录穿越** —— zip 条目名里带 `..` 的直接跳过

### 原生库怎么加载 ⚠️

`System.loadLibrary` 只认 APK 里的 `nativeLibraryDir` —— 那是**构建期**的路子。
下载版的 .so 在 `filesDir/native/`,所以改成 `System.load(绝对路径)`:

```kotlin
System.load(File(nativeDir, "libc++_shared.so").absolutePath)
System.load(File(nativeDir, "libnode.so").absolutePath)
System.load(File(nativeDir, "libdshengine.so").absolutePath)
```

**顺序不能换**: `libdshengine.so` 的 `DT_NEEDED` 是 `libnode.so`(SONAME 也是
`libnode.so`),Android linker 在同一 namespace 内**按 SONAME 复用已加载的库** ——
先把 libnode.so 加载进去,libdshengine.so 的 NEEDED 就对上了,不用改 RPATH。

实测(模拟器 android-34 / arm64-v8a):这样加载**一次成功**,没有
UnsatisfiedLinkError,也没有 `dlopen failed: cannot locate symbol`。

### 凭据改了必须重启引擎 ⚠️

引擎进程**启动时**就把 `settings.json` / `.zai.json` 读进内存了 —— 改了文件它
不知道。于是「保存了但还是用旧凭据」,用户看到的是 **「保存不了」**。

修法:保存后发一个带 `EXTRA_RESTART` 的 Intent 给 `EngineService`,它自杀
(写 dead 状态 + 杀进程),主进程的 `EngineController` 看到 dead 就按退避把新进程
拉起来 —— 新进程启动时重新读凭据。

`node::Start` 一个进程只能调一次,所以只能换整个进程,不能原地重启。

### 密钥里的空白字符必须去掉(0.29.2)

真机报出来的错:

```
API Error: Headers.append: "sk-cp-…ZsBvp dKQHRkJBa9…M1e9 bPMY4Avp3Fhwzf6Es"
is an invalid header value.
```

用户复制 key 时,下划线被打成了空格(输入法联想 / 软键盘长按空格 / PDF 复制
都会干这事)。Node 的 `Headers.append` **拒绝含空白字符的 header 值**,于是整个
请求在发出之前就炸了 —— 而且报错里会把**整个 key 打出来**,既难查又漏密钥。

修法在 `EngineCredentials.sanitized()`:`apiKey` 去掉**所有**空白字符
(`baseUrl` / `model` 只 trim 两端)。入口有三道:

- `EngineCredentialsStore.save()` —— 存进 DataStore 前就去掉;
- `EngineCredentialsStore.apply()` —— 物化文件前再去一次(**老版本存进去的
  带空格的值靠这一道洗掉**,否则用户升级 App 也不生效);
- 设置页保存时把去空白的值回填进输入框,并提示「已自动去掉 N 个空白字符」。

> 顺带修了一个 UI bug:凭据输入框原本用 `remember { mutableStateOf(stored.x) }`
> —— DataStore 的 flow 是异步的,首帧拿到的还是默认空值,`remember` 把它
> **永远留住**,于是用户打开设置页看到的是三个空框,以为没存上,重填一遍还会
> 把已存的冲掉(实测把 baseUrl / model 存成了空)。改成 `remember(stored.x)`,
> flow 一发值就回填。

### librg.so 必须回 APK(0.29.3)⚠️ 真机才暴露的坑

原生库按需下载之后,`librg.so` 也跟着进了下载包,解到 `filesDir/native/`,
软链 `files/bin/rg → filesDir/native/librg.so`。**模拟器(Android 14)跑得好好的**,
真机上 Grep / Glob 工具却全废:

```
spawn rg EACCES
```

AVC 日志:

```
deny  { execute }  untrusted_app  app_data_file  →  自己数据目录里的文件
deny  { execute_no_trans }  ...
```

**根因**:SELinux 只给了 `untrusted_app` 对 `app_data_file` 的 **execute**
权限(dlopen / mmap PROT_EXEC 要的那个),**没给 execve 要的 execute_no_trans**
—— 所以「用 dlopen 加载私有目录里的 .so」行,「execve 私有目录里的可执行文件」
不行。

> ⚠️ 更正一条早先的判断:这**不是**「部分 Android 版本才严格」,模拟器
> (Android 14)也一样拒 —— 当时只是没人测 Grep 工具本身(验收脚本只看
> 软链在不在)。`run-as` 手工跑能成功,是因为 run-as 跑在 `runas_app` 域里,
> 权限比 untrusted_app 宽。

迷惑性拉满:BashTool 照常(它 exec 的是 `/system/bin/sh`,系统分区),
引擎照常起(它是 **dlopen** libnode.so),只有 Grep / Glob 这类
**要 exec 自己目录里二进制**的工具废掉。

**解法**:`librg.so` 回到 APK 的 `lib/arm64-v8a/`,并开
`useLegacyPackaging = true`(0.29.0 之前那条 `extractNativeLibs` 的坑,
当时是为了内嵌引擎,现在为了 ripgrep 又得开回来)—— .so 解到
`nativeLibraryDir`(`/data/app/<pkg>/lib/arm64`,`apk_data_file` 标签,
平台自己就执行那里的 .so),软链指向它。

```kotlin
// EngineShims:优先 APK 里那份,拿不到再退回下载的那份
val apkRg = File(context.applicationInfo.nativeLibraryDir, "librg.so")
val downloadedRg = File(EnginePaths.nativeDir(context), "librg.so")
```

Node 运行时那 95MB **仍然不进 APK** —— 只有 4.4MB 的 librg.so 回来。
APK 39.5MB(比 0.29.2 的 40.9MB 还小一点:legacy packaging 把 .so 改成
不压缩存储,但 dex 那部分省回来的更多)。

**验收脚本必须真的 exec 一次**:软链存在 ≠ 能跑。`verify-local-engine.sh`
第 3 步现在跑 `files/bin/rg --version`,跑不出来就是坏了。

### 让 agent 在 Bash 里用 node(0.29.4)

`node script.js` / `node -e '…'` 在 BashTool 里可用 —— 用的就是引擎自己那套
Node 24 运行时,不是另装一个。

三件事凑成的:

1. **启动器进 APK**:`libdshnode.so`(4.6KB,node 启动器)放进
   `lib/arm64-v8a/` → 解到 `nativeLibraryDir`。**必须进 APK** ——
   私有目录里的文件 exec 不了(见上面那条 execute_no_trans)。
   `libnode.so` 本体(82MB)仍然在下载目录里。
2. **`files/bin/node` 软链**指向 `nativeLibraryDir/libdshnode.so`。
   PATH 里已经有 `files/bin`,所以 `which node` 找得到。
3. **`LD_LIBRARY_PATH` 指向下载目录**:启动器的 RPATH 只有 `$ORIGIN`
   (= nativeLibraryDir,那儿没有 libnode.so),DT_NEEDED 是
   `libnode.so` / `libc++_shared.so`。所以引擎进程的 env 里加
   `LD_LIBRARY_PATH=<filesDir>/native`,bash 和 node 都继承。

⚠️ **为什么 LD_LIBRARY_PATH 对引擎自己没用、对子进程却有用**:
引擎进程是 zygote fork 来的,`AT_SECURE=1`,动态链接器会忽略它自己 env 里的
`LD_LIBRARY_PATH`(所以引擎内的 Node 靠 `android-env.cjs` preload 猴补丁修 env)。
但引擎 **exec 出去的子进程**(bash、node)是重新 execve 的,auxv 由内核重新算,
`AT_SECURE=0` → `LD_LIBRARY_PATH` 正常生效。

另外加了个 `NODE_PATH=<引擎目录>/node_modules` —— agent 的 node 脚本能直接
`require('zod')` 之类引擎自带的依赖(只对 CJS 的 require 生效,ESM 不走这条)。

实测(模拟器):`node -e 'console.log(process.version)'` → v24.22.1;
`require.resolve('zod')` → 引擎的 node_modules;`node script.js` → 正常;
`process.cwd()` → `files/workspace`(agent 的工作区)。

### App 升级后软链会悬空 ⚠️

`nativeLibraryDir` 的路径带随机目录名(`/data/app/~~XXXX/pkg-YYY/lib/arm64`),
**App 升级后路径就变了**,旧的 `files/bin/rg`、`files/bin/node` 全变悬空软链。

而 `File.exists()` 对悬空软链返回 **false** → 判「软链不存在」→ 直接
`Os.symlink()` → 撞 `EEXIST` 失败 → 软链永远指着旧路径 → rg / node 全废。
**判存在必须用 `Os.readlink()`**(悬空软链照样返回它指向的字符串)。

这个坑跟 App 升级绑在一起,所以**每次升级后必须验证一遍软链指向**。


### 换下载源不用改 App

App 只认「一个 manifest 地址」。清单里写着 zip 的文件名与 sha256,App 按清单下载。
当前用局域网上的 Mac(`tools/serve-engine.sh`),将来换 GitHub release 只要把
manifest 放到那儿、填新地址即可 —— App 侧一行不用改。

### 已知没做的

- API key 仍是明文(落在 `filesDir/home/.zai.json`)
- 截屏授权的 UI 入口(`ScreenCaptureService` 有,首页按钮没接)
- `node-pty` / `sharp` 原生库
