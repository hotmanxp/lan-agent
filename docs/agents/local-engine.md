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

- **`node-pty`**:手机上缺原生库,当前路径下表现为 503(交互式终端用不了)。
  要支持得把 `libdsh_pty.so` 装到
  `node_modules/node-pty/build/Release/pty.node`。
- ~~`sharp`~~ **已解决(0.31.4)**:不装原生库,改用**手机自己的解码器** ——
  引擎包里 `node_modules/sharp/` 放的是我们的替身(`tools/engine-shim/sharp/`),
  它把图片处理转给主进程的 Android `BitmapFactory`(能力桥 `image_resize`)。
  见下面「十一、sharp 替身」。
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

### 5. 「我手机装了哪些 App」答不准(0.31.2)

**症状**:用户问「看看我手机上安装了哪些 APP」,agent 回「一共 278 个包,绝大部分是系统
组件,真正你手动装的第三方 App 只有 8 个左右」—— 用户装了那么多,明显不对。

**根因两层**:

1. **包可见性**:Android 11+ 不声明 `QUERY_ALL_PACKAGES` 时,系统会把包查询结果
   过滤成「可见包」,列表静默不全。这 App 是侧载自用不上 Play,所以该权限直接
   声明了(Play 上架会被拒,这里不 care)。
2. **分类靠模型猜**:`getInstalledApplications(0)` 把 200+ 个系统组件一起丢给模型,
   模型只能**按包名前缀猜**哪些是用户装的 —— 猜出来「只有 8 个」。

**改法**:分类交给系统。`ApplicationInfo.FLAG_SYSTEM` → 系统预装,
`FLAG_UPDATED_SYSTEM_APP` → 预装但被用户更新过,其余 → 用户装的。
`phone_list_apps` 加 `scope` 参数:

| scope | 返回 |
|-------|------|
| `user`(默认) | 只列用户装的 |
| `system` | 只列系统预装的 |
| `all` | 全列 |

每条多带 `userInstalled` / `updatedSystemApp` / `launchable` / `versionName` /
`firstInstallTime`;顶层多带 `totalInstalled` / `userInstalledCount` /
`systemAppCount` —— 回答「装了几个」用这两个数,别让模型数条目。

⚠️ 用 `getInstalledPackages(0)` 而不是 `getInstalledApplications(0)`:后者拿不到
`firstInstallTime` / `versionName`。


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
- `node-pty` 原生库(`sharp` 已用替身解决,见「十一、sharp 替身」)

---

## 十、手机端专用主 Agent(0.29.5)

参考 core 的 `server/mainAgents-weixin.ts`(微信通道的专用 agent),给手机端
也做一份:**提示词讲清「你在手机上跑」,工具池砍掉依赖电脑的那些**。

### 为什么用「外置 agent」而不是改 core

core 的主 Agent 有三个插槽(systemPrompt / tools / mcp),可以被 agent 配置整体
替换。手机专属这套有两条理由不塞进 core:

1. 它是**手机专属**的(工作区是 app 私有目录、shell 是 toybox、进程随时被杀),
   不该跟着 core 升级漂到桌面实例上;
2. 改 core 要重打 7.5MB 的 core bundle → 引擎包换指纹 → **用户重下 34MB**。
   外置文件跟着 APK 走,改一句提示词只要重发 APK。

### 机制

- 引擎启动时 `initAgentRuntime` 扫 `$ZAI_DATA_DIR/main-agents/*.js`,逐个
  `import()`,取 default 导出(函数则 `await raw(ctx)`)注册进 AgentRegistry
  (core: `server/agentRegistry.ts` 的 `loadUserAgents`)。
- `settings.json` 的 `mainAgent: "mobile"` 让**新会话**选中它 —— 会话在第一次
  prompt 时把 agent 冻结进 `transcript.meta.mainAgent`,两个插槽按会话应用。
- 落地:`app/src/main/assets/local-engine/mobile-main-agent.js` →
  `EngineAgentProfile.apply()` 每次起引擎把它拷到
  `$ZAI_DATA_DIR/main-agents/mobile.js`,并把 `mainAgent` 合并进 settings.json。

⚠️ **settings.json 是「读改写」不是覆盖** —— 同一个文件里还有引擎要的 `env`
(模型地址 / 密钥)和 `permissions`(免批准模式),覆盖会把它们抹掉。

### 提示词写了什么

英文(项目规定系统提示词用英文,回复语言由提示词内的指令控制为中文),内容是
**这个 agent 实际所处的环境**,不是泛泛的「你在手机上」:

- 设备与工作区(`files/workspace`,不是任何项目的 checkout)
- shell 是 toybox `sh`(以 `bash` 之名暴露),没有 apt/pip/npm/git,装不了包
- `node`(v24)可用、只核心模块;`rg` 可用
- 沙箱:只能读写 app 私有存储,碰不到用户的电脑
- 手机能力层(`mcp__phone-bridge__phone_*`:截屏 / 通知 / 剪贴板 / 应用列表 /
  启动应用 / 系统设置)
- **进程随时会被杀** —— 长任务要增量交付,不许承诺「回头再做」
- 明确列出「做不到」的那些:碰不到电脑、装不了包、开不了浏览器、没有外部 CLI agent

### 工具策略:黑名单,不是白名单

工具池随 mod / 权限 / 环境浮动(mod 贡献的工具就在里面),白名单会把手机能力层
误杀。砍掉的是「正常工作前提在手机上不成立」的那些:

| 工具 | 为什么砍 |
|---|---|
| `CliAgent` | 外置 CLI agent(opencc / dsh 二进制),手机上没有 |
| `CronCreate/Delete/List` | 引擎进程随时被杀,定时任务不可靠(提示词里也写明了) |
| `Enter/ExitWorktree` | git worktree,手机工作区不是仓库 |
| `Enter/ExitPlanMode` | 手机 UI 没有计划审批交互 |
| `Monitor` / `RemoteTrigger` / `WorkflowTool` / `SendUserFile` / `Brief` | 面向宿主机进程与桌面端流程 |
| `LSP` | 要语言服务器二进制 |
| `PowerShell` / `snip` / `WebBrowser` | 桌面平台的东西 |
| `Config` | 模型/凭据由 App 设置页管理,引擎侧改了会被下次物化覆盖 |

保留:`Read/Write/Edit/Glob/Grep/Bash`、`Agent`(子代理在同一个引擎进程里跑)、
`AskUserQuestion`(App 原生渲染选项卡)、`PresentFile`(App 原生渲染文件卡)、
`WebSearch`、`Skill`、`TodoWrite`、`Task*`、以及全部 mod 工具。

### 一个绕路:PresentFile

`PresentFile` **只由 core 的 `default` agent 挂**,外置 agent 拿不到那个工具
对象(core 没导出它)。所以手机 agent 的 tools 槽先**沿用 default 的槽**
(拿到 PresentFile),再在上面做过滤。

拿 default 的槽要 import core。实测:`require('@zn-ai/zn-agent-core')`
(靠 NODE_PATH)在「从 `$ZAI_DATA_DIR` 由 `import()` 加载的 CJS 文件」这个上下文里
**解析失败**(同一个 NODE_PATH 在普通 node 子进程里是好的)—— 最后用
**按文件 URL 动态 import**:`<引擎目录>/node_modules/@zn-ai/zn-agent-core/opencc-core.mjs`
(引擎目录从 `OPENCC_MODS_DIR` 反推)。

### 实测(模拟器)

工具池(引擎日志里的 `[mobile-agent] tool pool =`):

```
Agent, AskUserQuestion, Bash, Edit, Glob, Grep, PresentFile, Read, Skill,
TaskStop, TodoWrite, WebSearch, Write,
mcp__minimax-image__generate,
mcp__phone-bridge__phone_{clipboard,list_apps,notifications,open_app,screenshot,settings},
subagent_control
```

砍掉的:`CliAgent, CronCreate, CronDelete, CronList, EnterPlanMode,
EnterWorktree, ExitPlanMode, ExitWorktree, Monitor, snip`。

问它「你在什么环境跑」,答的是 Android 手机 + app 私有沙箱 + `files/workspace`
+ node v24.22.1 + 手机能力清单 ✅。


---

## 十一、sharp 替身(0.31.4)

### 问题

core 的图片处理(压缩 / 缩到 1568px / 换格式)全走 sharp。sharp 是**原生
模块**,预编译包只覆盖 darwin / linux / win / freebsd,**没有 android-arm64**
→ 引擎里 `import('sharp')` 直接抛 `ImageProcessorUnavailableError`。

症状不是「完全看不了图」:

- 原图 base64 在 5MB 以内 → core **原样透传**(让 API 服务端自己缩),能看;
- 超过 5MB(手机相机出的 12MP 大图,典型 4–12MB)→ **Read 工具直接失败**,
  报错写着 `Image support is not installed. Install 'sharp' …` —— 手机上
  根本没法照做这句话。

### 解法:不装原生库,改用手机自己的解码器

引擎包里 sharp 是 esbuild 的 **external**,运行时才从 `node_modules` 解析。
所以在引擎包里放一个**自己的实现**就行 —— core 和 opencc-web 一行都不用改:

```
引擎(core)→ import('sharp') → 替身(tools/engine-shim/sharp/index.mjs)
         → HTTP 能力桥 /cap/image_resize
         → 主进程 CapabilityService → Android BitmapFactory
         → 采样解码 + 缩放 + 重编码 → base64 回来
```

- **替身源码**:`tools/engine-shim/sharp/{index.mjs,package.json}`
- **打进引擎包**:`tools/build-engine-pkg.mjs` 拷到 `<out>/node_modules/sharp/`
- **Kotlin 端**:`PhoneCapabilities.imageResize` + `CapabilityService` 注册
  `image_resize`
- **只实现 core 用到的**:`metadata / resize / jpeg / png / webp / toBuffer`。
  `metadata()` 本地读 magic bytes(PNG IHDR / GIF 头 / WebP VP8 系 / JPEG SOF
  扫描),不过桥 —— 它是热路径。

### 为什么不用 wasm 版 sharp

`@img/sharp-wasm32` 是能跑的(sharp 0.35 在认不出平台时会自动回落到它,
实测 3000×2000 缩到 1568 宽约 30–40ms),但要背 9MB 下载体积 + 整图 RGBA
的内存。手机本来就有原生图像栈,采样解码(`inSampleSize`)的内存友好度
远好于 wasm libvips —— 一张 4000×4000 的 PNG 硬解是 64MB,不采样几张就
能把堆推爆。

### 几个实现细节

- **传路径不传 base64**:替身先把原始字节写到 `TMPDIR`,主进程直接
  `decodeFile` —— 一张 5MB 的图不用在堆里过一遍 base64(6.7MB 字符串 +
  5MB 解码结果,手机上很奢侈)。写文件失败才退回 base64。
- **JPEG 要铺白底**:带 alpha 的 PNG 直接压 JPEG,透明区会变**黑**。
- **EXIF 方向**:相机出的图常是「像素横着 + EXIF 说该转 90°」,
  `BitmapFactory` 不管方向 —— 不解的话模型看到的是躺着的图。
- **没有缩放请求时的兜底**:全解一张 12MP 图是 48MB,采样到长边 ≤ 4096。

### 实测(模拟器 asr_repro / android-34)

- 能力桥单测:4000×3000 / 11.6MB 的 JPEG → 1568×1176 / 780KB,**0.21s**
- 引擎里 agent `Read big.jpg` → 成功,模型如实描述出「灰色带噪点」
  (图是随机噪声)+ 报出 4000×3000
- 手机侧日志能看到 core 的压缩阶梯:
  `4000x3000 → 4000x3000 → 3000x2250 → 2000x1500 → 1000x750 → 600x450`

## 十二、搜索卡住不超时(0.31.5)

### 症状

真机上让 agent 搜索,工具卡**永远转圈**,超过 30 秒也没有任何超时错误。
用户截图里是「详情」弹层里两行「已搜索 /data/data/…/files/w…」,两个转圈都不结束。

### 根因:三层,逐层剥

1. **`rg` 本身可能永久阻塞**。`rg` 递归遍历时遇到特殊文件(命名管道等)
   会卡在不可中断 I/O 里 —— 真机上存储慢,概率不低。
2. **Node 的 `execFile` 超时救不了场**。`execFile` 的 `timeout` 只是
   `setTimeout(kill)`,**然后等子进程的 `'close'` 事件**;子进程卡在
   D 状态收不回来 → 回调永不触发 → promise 永不 settle → 工具调用既不
   返回也不报错。
3. **core 侧的独立看门狗能救,但它在引擎包里**。opencc-web `3e534f03`
   给 ripgrep 加了独立看门狗(不等 `'close'`,直接以 ETIMEDOUT 结束),
   `d694e188` 加了 `RIPGREP_PATH` 显式路径。**但 core bundle 打在
   引擎包 zip 里**,用户手机上的引擎包是旧版 → 没有看门狗 → 无限转圈。

   验证:模拟器上两个引擎包**都含**看门狗,引擎在 ~30s 后正确返回了
   超时错误(引擎侧 `CLAUDE_CODE_GLOB_TIMEOUT_SECONDS=30`)→ 引擎链路本身
   是好的,问题坐实在「手机上的引擎包过旧」。

### 解法:App 侧兜底(不依赖引擎包版本)

`ui/ToolCallWatchdog.kt`(纯 Kotlin,可单测)+ `ui/AgentSessionStore.kt`:

- store 在收到 `runtime.tool_call` 时记 `{key → 开始时刻}`(**只记第一次** ——
  同一个 key 再来的 `tool_call` 是同一轮重试,重置计时等于把等待又往后推);
  `tool_result` / `markToolError` / `hydrate` / `clearAll` 都清掉。
- `expireStalledToolCalls(now)` 扫这张表:超时且仍在跑 → 换成错误卡
  (`isError = true` + 说明「引擎没有返回」)。
- `ui/AgentSessionScreen.kt` 每 5s 调一次(不用 15s 的 `clockNow`:阈值 60s,
  15s 跳法最坏要 75s 才提示)。

**阈值 60s 必须大于引擎侧最坏情况**(30s rg 超时 + 5s core 看门狗 = 35s),
否则会在 core 正常报错之前抢先把卡盖掉。**Bash / Agent 不纳入** ——
core 允许 Bash 跑 10 分钟(`BASH_MAX_TIMEOUT_MS`),套 60s 会误杀正常任务。

**判超时后还要中断这一轮**:引擎侧那次调用其实还挂着,只改 UI 不中断的话,
用户下一条消息会永远排在那个不结束的轮次后面(表现是「发送了但没反应」)。
所以 `expireStalledToolCalls` 返回超时的 key 之后,App 调
`POST /api/agent/abort` 中断这一轮。

### 顺带修:core 的超时文案写死了「20 seconds」

core 的 `RipgrepTimeoutError` 消息写死 20 秒,但超时由
`CLAUDE_CODE_GLOB_TIMEOUT_SECONDS` 决定(手机上 App 设的是 30)→ 用户看到的
数字和实际等待时长对不上,排查容易被带偏。改成用 `getRipgrepTimeoutMs()`
的真实值。

### ⚠️ 结论落到用户动作

**引擎包过旧 → 搜索卡死无超时**。修好之后仍要让用户在
「设置 → 本机引擎」**重新下载引擎包**(指纹变了,旧包不会被覆盖),
否则 core 侧仍没有看门狗,只是 App 侧会在 60s 后兜底报错。

