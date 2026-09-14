# LAN Agent

简单 Android App,把局域网内多个 opencc-web 实例的入口收成卡片列表,
点击卡片进入 WebView 详情加载对应 URL(默认目标是 `/m` 移动 Agent 页面)。
**0.9.0** 起部分能力收回原生:实例管理、实例卡「会话」进入的 Agent 会话列表与
会话详情(含发消息 / 实时流式 / 权限确认 / 语音输入 / 发图片)都不再走 WebView。
`/agent-session-*` 两个屏的输入条形态**0.9.2** 先做成单胶囊,**0.10.1** 定稿为
WorkBuddy 的双行白卡(上排文本域 / 下排语音·模型·`+`·发送钮)。
**0.10.0** 把整个 App 的视觉体系换成 WorkBuddy 那一套(浅灰页底 + 白色卡片 +
品牌青绿 + 官方机器人形象),并给助手正文接了真正的 **Markdown 渲染**;
**0.10.1** 再修两处「还是不像」的地方:**用户气泡改中性浅灰**(不是品牌绿)、
**输入条改双行白卡**(上排文本域 / 下排工具条,发送钮常驻)。

## 0.10.0 / 0.10.1 新增功能 — WorkBuddy 视觉体系 + Markdown 渲染

### 1. 配色:关掉 Material You,固定 WorkBuddy 色板

上一版跟手机壁纸取色,截图里整屏泛紫。现在(App 内所有页面一起变):

| 用途 | 亮色 | 深色 |
|------|------|------|
| 页面底 | `#F8F8F8` | `#141517` |
| 卡片 / 输入条 / 弹层 | `#FFFFFF` | `#1F2124` |
| 正文 | `#1F1F1F` | `#ECEDEF` |
| 次要文字 | `#8C8C8C` | `#9AA0A8` |
| 发丝线 | `#EBEDF0` | `#2B2D31` |
| 品牌青绿(主按钮 / 发送 / 运行中) | `#0CC8A6` | `#35D6B6` |

色板只有一个来源:`ui/LanAgentTheme.kt` 的 `WbPalette` + `LightScheme/DarkScheme`。
顶栏不再是白条(跟页面连成一片),卡片靠白底 + 发丝描边浮在灰底上。

### 2. 机器人形象 + 启动图标

- 会话空态 / 首页空态用 WorkBuddy 官方机器人形象(`res/drawable-nodpi/wb_mascot.png`)
  + 问候语「LAN Agent,我帮你」(文案在 `strings.xml` 的 `agent_session_empty_title`)
- 启动图标:品牌青绿底 + 机器人头(`mipmap-anydpi-v26` 自适应图标,前景
  `drawable-nodpi/ic_launcher_foreground.png` 已按 108dp 安全区留白)

### 3. 助手正文 Markdown 渲染(新增 `ui/Markdown.kt`)

以前只认 ``` 围栏代码块,其余原样输出 —— Agent 回的标题 `#`、列表 `-`、
表格、粗体全都变成了裸符号。现在自研了一个**子集**渲染器(不引依赖):

- **块级**:`#` 标题(1–6 级) · 段落 · ``` 围栏代码块(带语言标签 + 复制按钮) ·
  `>` 引用 · `-`/`1.` 列表(可嵌套,`- [ ]` / `- [x]` 渲染成勾选框) · 表格(横滚) · `---` 分割线
- **行内**:`**粗**` `*斜*` `` `代码` `` `~~删除线~~` `[文字](链接)` 裸链接 `\` 转义
- **流式安全**:未闭合的 ``` 直接当代码块渲染(而不是把后面内容全吞掉),半截
  `**` 原样输出 —— SSE 边收边渲染不会崩
- 思考过程、工具入参/输出也走同一套渲染(工具输出用代码块 + 复制)

### 4. 用户气泡 + 输入条(0.10.1 精修)

0.10.0 换了配色,但两处跟 WorkBuddy 放一起仍然「一眼看得出不是一个产品」,0.10.1 修掉:

**用户消息气泡** —— 改**中性浅灰 `#E2E4E3`**(之前是品牌薄荷绿 `#DDF6F0`),
**四角同半径 18dp**(去掉右下角那个 4dp 尖角尾巴),长消息可以占到接近满宽。
品牌青绿只留给发送按钮 —— 这是 WorkBuddy 与「绿色气泡 IM」的分水岭。

**输入条** —— 改成 WorkBuddy 的**双行白卡**:

```
┌────────────────────────────────────────────┐
│ 输入消息…                                   │  ← 上排:整行都是文本域
│ (波形)  ◍ deepseek-v4.1 ⌄   (＋)      (➤)  │  ← 下排:工具条 + 发送钮
└────────────────────────────────────────────┘
```

- 白卡 24dp 圆角 + 极轻投影,**没有描边**
- 下排从左到右:语音 · 模型 chip(点开切模型)· `+`(添加图片 / 粘贴)· 发送钮
- **发送钮常驻**,空输入时是浅蓝灰禁用态(点了没反应),有内容才变青绿;不再
  像上一版那样「空输入时按钮变成 `+`」—— 那样按钮会随输入状态跳变
- 删掉了卡下方那排「图片 / 粘贴 / 模型 / 更多」图标:WorkBuddy 没有这行。
  功能没丢 —— 图片/粘贴收进 `+` 面板,模型在卡内 chip 上
- 占位文案跟着 WorkBuddy 改成「**输入消息…**」(之前是「发消息给 Agent…」)

## 改入口

**首页 Card 列表**(DataStore 持久化)有 3 种改法:

1. **APP 内编辑模式** — 首屏右上 `✎` 进编辑模式,长按拖拽换位,点 `🗑` 删除,
   点 `+` 加新卡。改完即时写 DataStore,下次启动还是这些。
2. **QR 扫码进入** — 首屏右上 QR 图标(CameraX + ML Kit Barcode),扫 zai 分享的 URL
   二维码直接跳 WebView,**不写** DataStore。
3. **改 seed 卡片** — 编辑 [`app/src/main/java/io/github/hotmanxp/lanagent/data/Cards.kt`](app/src/main/java/io/github/hotmanxp/lanagent/data/Cards.kt)
   里的 `defaultCards` 列表,改完 `./gradlew :app:installDebug` 重装即可。
   **只影响卸载重装后的首次启动**(已有数据从 DataStore 读)。

**原生实例管理屏**(服务端实例定义,通过 `/api/instances` 同步)有 1 个入口:

- 首屏右上 `Storage` 图标 → 弹出 InstancesScreen → 右下 `+` 浮动按钮 →
  选手动表单 / 目录选择器(拉 `/api/fs/picker`)/ QR 扫码;**0.8.0** 起
  顶栏多一个 `RocketLaunch` 图标,快捷创建「任务工厂实例」(`app='task-factory'`)。
  **新加的是服务端实例定义,跟首页 Card 列表是两套数据**。

如目标 IP 不在白名单,还要编辑
[`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml)
加一行 `<domain includeSubdomains="true">你的.IP</domain>`。

## 0.9.2 新增功能 — 输入条对齐 WorkBuddy

> **注意:** 本节描述的「单胶囊」形态已被 **0.10.1 的双行白卡**取代(见上)。
> 保留下面的记录只为说明演进过程。

上一版的输入条是「一个圆角框 + 框外再挂一个圆形发送按钮」，跟参考截图差得明显。
0.9.2 按 WorkBuddy 手机端重做成**单胶囊**：

```
┌───────────────────────────────────────────┐
│  (波形)   发消息给 Agent…             (+)  │
└───────────────────────────────────────────┘
```

**右侧按钮三态**：有输入 → 发送箭头（实心圆）；运行中 → 停止（红实心圆）；
空输入 → `+`（无底色），点开面板「添加图片 / 粘贴剪贴板」。

**左侧语音是真的能用**（不是装饰）：点了走系统语音识别，边说边往输入框里填字
（`onPartialResults` 实时回填）。设备没有识别服务 / 没给录音权限时会明确提示，
按钮也会置灰。**注意识别需要联网**。

**图片附件**：`+` → 「添加图片」→ 系统相册选择器 → 自动压缩（长边 1600 / JPEG 85，
单张 200–500KB）→ 随消息一起发。最多 4 张，缩略图挂在输入条上方，点右上角 X 移除。
相册里选 HEIC / PNG 也没事，客户端统一转成 JPEG 再上传。

**顶栏也瘦了**：刷新 / 在网页打开 / 会话 ID / 目录 / 模型 / 消息数 全部收进
**副标题点开的面板**（点副标题右侧的 `›`）。顶栏现在只有「返回 + 标题 + 副标题」，
跟截图一致；只有在真的运行中/出错时才会多一个状态标签。

**空会话**从一行小灰字改成居中大图标 + 大字。

## 0.9.0 新增功能 — 原生 Agent 会话

以前只能点「打开」把 `/m` 塞进 WebView;现在实例卡多一个「会话」按钮,进**原生**
会话列表 → **原生**会话详情。视觉参考 WorkBuddy 手机端对话页。

> **0.9.1 修复**:`GET /api/agent/sessions` 的 `updatedAt` 来自 Node `fs.Stats.mtimeMs`,
> 是**带小数的浮点毫秒**(实测 115/115 条全是,不是偶发)。原先按 `Long` 声明,
> 真机上直接 `Unexpected symbol ':' in numeric literal at path: $.sessions[0].updatedAt`,
> **会话列表整页报错打不开**。现在所有时间戳字段走容错的 `EpochMsSerializer`
> (容忍整数 / 浮点 / 字符串),并补了 `app/src/test/` JVM 单测把这类 wire 坑钉住:
> `./gradlew :app:testDebugUnitTest`。

**入口**:InstancesScreen 实例卡 → 动作行「会话」(Chat 图标,实例有运行端口就能点)
→ 会话列表 → 点某条进详情。详情页顶栏 ↗ 图标可随时回退到 WebView
(`/m?sid=<当前会话>`)。

**能力**:
- **会话列表** — 该实例 cwd 下的会话(标题 / 模型标签 / 相对时间 / sessionId),
  5s 自动刷新 + 顶栏手动刷新;顶部「新建会话」按钮直连 `POST /api/agent/sessions`
- **历史渲染** — 用户消息靠右气泡、助手正文靠左(` ``` ` 代码块等宽底框)、
  思考过程折叠卡(默认收起)、工具调用折叠卡(状态 chip + 可展开入参/输出)
- **实时流式** — 直连 `/api/event?sid=` SSE,`runtime.delta` 逐字渲染;
  断线自动重连 + 指数退避 + 按 seq 去重
- **发消息 / 中断** — `POST /api/agent/prompt`(本地乐观追加用户消息)+
  `POST /api/agent/abort`(输入条右侧变红色停止按钮)
- **图片附件**(0.9.2) — `+` →「添加图片」→ 系统相册选择器 → 压缩成 JPEG
  (长边 1600 / Q85)→ 随消息作为 `contentBlocks` 发出,最多 4 张
- **语音输入**(0.9.2) — 输入条左侧波形图标,调系统 `SpeechRecognizer`,
  边说边回填(`onPartialResults`);需要联网 + 设备有识别服务
- **队列** — `queue.changed` 驱动「排队中」条,可 steer(`queue/steer`)或取消(`queue/cancel`)
- **任务清单** — `v2_task.changed` 驱动的可折叠「任务清单 N/M」条
- **权限确认 / 问询 / 文档审核** — `prompt.permission` / `prompt.ask` / `prompt.approve`
  三个 SSE 事件各渲染一张卡,直接在手机上「允许/拒绝」「提交/拒绝」「批准/驳回」
  (审核卡可展开看文件内容)

**架构新增**:`data/AgentModels.kt`(wire 模型)+ `data/AgentApi.kt`(HTTP +
`callbackFlow` 版 SSE)+ `ui/AgentSessionStore.kt`(transcript 归一化 + 事件 reduce
状态机)+ `ui/AgentSessionsScreen.kt` + `ui/AgentSessionScreen.kt` +
`ui/AgentSessionViews.kt`(消息渲染组件)。

**协议要点**(踩过的坑都写在 `AGENTS.md` §14):`/api/*` 不需要 token;SSE 不要传
`topics`(白名单缺 `prompt.*` / `queue.changed`);`Last-Event-ID` 与 `id:` 语义错位
所以去重要在客户端按 seq 做;transcript 的 `timestamp` 有数字/字符串两形态。

## 0.6.0 新增功能

相比 0.1.x 单卡片模式,0.6.0 在保留 HomeScreen 卡片列表的同时新增**原生实例管理屏**:

- **原生实例管理** — 首屏 Storage 按钮 → InstancesScreen 拉 `/api/instances`,
  2.5s 轮询;InstanceCard 对标 web Instances.tsx(状态 Tag / LAN Switch / 启动端口
  / 运行端口 / cwd / PID / 启动时间 / 运行时长 / 最后心跳 / 错误)
- **五种动作** — 启动 / 停止 / 重启 / 删除(带二次确认)/ 打开(直接跳 WebView)
- **三种创建方式** — 手动表单(name / cwd / LAN / 端口模式 / 实例类型)/ 目录选择器
  (拉 `/api/fs/picker`)/ QR 扫码(CameraX 1.3.4 + ML Kit Barcode 17.3.0)
- **后台保活** — `service/WebViewKeepAliveService`(dataSync foreground service),
  持 detached WebView,Activity onPause 后 WebView 的 SSE / WebSocket / long-poll
  仍跑;30 分钟 `PARTIAL_WAKE_LOCK` 超时兜底,API 34 用 3-arg `startForeground`
- **WebView 文件上传** — `<input type="file">` 走 `onShowFileChooser` →
  手动构建 pickIntent 绕过 OEM ROM `params.createIntent()` bug → 优先用
  `window.lanAgentAttachImages` bridge 把 `content://` URI 的 bytes 转 base64
  注入 `<input>`(WebView 标准转换在 content:// 上不可靠)
- **`onReceivedError` 静音** — LAN 工具 ERR_FAILED 太频繁,Snackbar 噪声无意义

**架构新增**:`LanAgentApp`(Application,注册 WebViewKeepAlive 通知 channel)
+ `service/WebViewKeepAliveService.kt` + `data/InstancesApi.kt`(OkHttp 4.12.0
+ PatchValue 三态)+ `data/InstanceModels.kt` + 6 个 ui/ 文件(InstancesScreen
/ InstanceCard / InstanceFormat / CreateInstanceDialog / EditPortDialog
/ DirectoryPickerDialog / ScanQrScreen)

**manifest 新增权限**:READ_MEDIA_IMAGES / CAMERA / FOREGROUND_SERVICE
/ FOREGROUND_SERVICE_DATA_SYNC / WAKE_LOCK / POST_NOTIFICATIONS

版本号: `versionCode 5 → 22`,`versionName "0.1.4" → "0.6.0"`。

## 0.8.0 新增功能 (实例类型:标准 / 任务工厂)

对齐 opencc-web `InstanceDefinition.app` 字段,把"标准实例 vs 任务工厂实例"
作为新的实例分类维度暴露给手机端:

- **`InstanceAppProfile` 枚举**(只读)— Kotlin enum 名 `TaskFactory`,
  序列化字符串字面量 `'task-factory'`(`@SerialName` 桥接)
- **`InstanceSnapshot.app` 字段** — 仅 `task-factory` 实例存在;`null` / 缺省
  = 标准实例。**创建后不可改**(PATCH 不接受 `app` 字段)
- **「实例类型」 Radio.Group**(`CreateInstanceDialog`)— 标准实例 / task-factory,
  对齐 web 端 `Instances.tsx` 的 `app-radio`
- **「新建任务工厂实例」快捷按钮**(顶栏 `RocketLaunch` 图标) — 打开 Modal 时
  预选 `app='task-factory'` 并预填 `currentCwd`,对齐 web 端
  `new-task-factory-instance`;FAB 「新建实例」 仍走标准实例路径
- **`TaskFactoryTag`** — 实例卡片头部 name 旁显示橙色 `task-factory` tag,
  与历史 `runtimeCore` tag(inproc/spawn/repl)的橙黄配色同色系但底色更暖,
  一眼区分;`runtimeCore` 字段本身于 2026-09-12 opencc-web 阶段 3 删除
- **`/api/instances` POST 新增 `app` body 字段** — 仅 `undefined` 或
  `'task-factory'`,`null` / 未知字符串 400

## 0.7.x 新增功能 (SSH 启动 zai)

**架构新增**:`model/SshHost.kt` + `data/SshRepository.kt`(独立 DataStore `lan_agent_ssh_hosts`)
+ `ssh/JschClient.kt`(JSch 封装)+ `ssh/ZaiLauncher.kt`(命令预设)
+ `ssh/ZaiPortProbe.kt`(OkHttp 端口探测)+ `ui/SshHostListScreen.kt`
+ `ui/EditSshHostDialog.kt`。依赖加 `com.jcraft:jsch:0.1.55`(Tencent mirror 已代理)。

**核心特性**:

- **多 SSH host 支持** — DataStore 持久化,可配置多台电脑
- **启停命令预设** — start 用 `nohup pnpm --filter @zn-ai/zai dev -- --lan > /tmp/zai.log 2>&1 & disown`;
  stop 用 `pkill -f 'pnpm.*zai.*--lan'`;日志查 `tail -50 /tmp/zai.log`
- **PATH 兜底** — sshd 默认 PATH 不带 Homebrew,命令前缀
  `source ~/.zshenv 2>/dev/null; source ~/.bashrc 2>/dev/null;`
- **端口探测自动跳转** — exec 成功后 OkHttp 1s × 5 次轮询 9201,200 → 自动跳 WebView
- **失败兜底** — sheet 内「查看日志」按钮,再 exec 一次 tail
- **密码明文存储** — 与 Card 一致(Phase 2 接受,后续可上 Keystore)

## SSH 启动 zai (0.7.x)

当电脑关机或 zai 没跑起来时,首屏右上 `>_` (Terminal) 图标进 SSH 主机列表,
点「启动 zai」按钮即可通过 SSH 远程执行 `nohup pnpm --filter @zn-ai/zai dev -- --lan`,
启动成功后自动跳到 Instances 实例管理页。

**前置条件(Mac 端)**:

1. **开启 SSH 远程登录**: 「系统设置 → 通用 → 共享 → 远程登录」(macOS 13+),
   或「系统设置 → 共享」(macOS 12)。勾上后记下当前 Wi-Fi 的 IP。
2. **pnpm 在 PATH**: zai 用 `pnpm` 启动,sshd 默认 PATH 不带 Homebrew 的
   `/opt/homebrew/bin`。所以命令模板已自动前缀 `source ~/.zshenv 2>/dev/null;
   source ~/.bashrc 2>/dev/null;`,如果还是找不到 pnpm,把 `export PATH=...`
   写进 `~/.zshenv`。
3. **opencc-web 路径**: 默认 `~/code/opencc-web`(命令模板硬编码)。
   改了路径就编辑 `ssh/ZaiLauncher.kt` 的 `OPENCC_WEB_DIR`。

**APP 内配置步骤**:

1. 首屏右上 `>_` 图标 → SSH 主机列表页
2. 点 `+` 加一条:name(随便起)/ host(电脑 LAN IP)/ port(`22`)/ user(Mac 用户名)/ password
3. 保存后列表多一条,点该条「启动 zai」按钮 → 弹半屏 sheet 显示:
   - 执行中 → exit code + 耗时
   - exit code = 0 + 端口探测 5 次内 200 → 自动跳 WebView
   - 失败 → sheet 显示「查看 /tmp/zai.log」按钮,点了显示最近 50 行日志

**进程存活**:用 `nohup ... & disown` 让 pnpm 从 SSH shell 脱离,
SSH session 关闭后 zai 继续跑。日志在 Mac 的 `/tmp/zai.log`。

**已知坑**:

| 现象 | 排查 |
|------|------|
| 连接超时 `connect failed` | Mac 远程登录没开 / IP 不对 / 端口被防火墙挡 |
| `Auth fail` | 密码错 / Mac 用户没勾「允许远程登录」 |
| exit code = 127 (`command not found`) | pnpm 不在 PATH,把 `export PATH="/opt/homebrew/bin:$PATH"` 写进 `~/.zshenv` |
| exit code = 0 但端口探测失败 | `cd ~/code/opencc-web` 路径不对,日志会显示 `cd: ...: No such file or directory` |
| exit code = 0 + 启动成功但跳不到 WebView | 端口探测超时(<5s),说明 pnpm 还在启动;手动点 Storage 进 InstancesScreen 看 |

**底层实现**:JSch 0.1.55(`com.jcraft:jsch`,~250KB,Maven Central 最新版本),exec 提交后立即返回;
端口探测用现有 OkHttp 4.12.0 轮询 `http://host:9201/instances`,1s × 5 次。

## 编译 & 装

```bash
cd /Users/ethan/code/lan-agent
./gradlew :app:installDebug
adb shell am start -n io.github.hotmanxp.lanagent/.MainActivity
```

要求: Android 真机 API 26+ 已连 adb。

## 验收清单(手动)

启动 opencc-web 服务端(另开终端):

```bash
cd /Users/ethan/code/opencc-web
pnpm --filter @zn-ai/zai dev -- --lan
```

确认桌面浏览器能访问 `http://<本机 IP>:8101/m` 看到 MobileAgent 页,
以及 `http://<本机 IP>:9201/instances` 看到实例管理页。

1. **服务端就绪**:`/m` 和 `/instances` 在桌面浏览器都可访问
2. **编译**: `./gradlew :app:assembleDebug` 出 APK 无报错
3. **安装**: `./gradlew :app:installDebug` 装到 Android 真机(API 26+)
4. **冷启动**: App 启动看到 TopAppBar "LAN Agent" + **5 张 seed 卡片**
   (Instances 实例管理 / opencc-web / opencc-web-dsh / code-opencc / code-dash)
   + 顶栏右侧 **4 个 IconButton**(QR 扫码 / Storage 实例管理 / ✎ 编辑模式 / + 添加)
5. **实例管理**: 点 Storage → InstancesScreen 拉 `/api/instances` →
   看到轮询卡片列表(状态 Tag、LAN Switch、启动端口、运行端口、
   cwd、PID、启动时间、运行时长每 30s 刷新、最后心跳相对时间)
6. **实例操作**: 点任一非当前实例的「启动/停止/重启/删除」 → 看到 loading +
   状态 Tag 变化(2.5s 内下一轮 polling 反映新状态)
7. **创建实例(手动)**: InstancesScreen 右下 `+` → 表单填名称 / cwd / LAN /
   端口模式 → 创建,列表多一张卡
8. **创建实例(目录选择)**: cwd 输入框点「浏览」 → DirectoryPickerDialog
   拉 `/api/fs/picker` → 进子目录 / 上一级 / 主页 → 点「选择当前目录」回填 cwd
9. **创建实例(QR 扫码添加)**: 首屏 QR 图标 → 相机权限弹窗 → ScanQrScreen →
   扫 zai 实例管理页导出的 URL 二维码 → 直接跳 WebView 加载 URL
   (注意:此路径只进入,不创建服务端实例)
10. **WebView 渲染**: 点首页任一非实例管理卡片 → 进 WebView(无 App 顶栏,
    只有右中浮 28dp 圆形刷新按钮),加载 opencc-web MobileAgent 页面
    (列表、输入框、抽屉);首次会弹媒体权限(READ_MEDIA_IMAGES)选允许
11. **WebView 刷新**: 点浮动刷新按钮 → 弹出「已刷新」 Snackbar 确认
12. **WebView 返回栈**: 系统返回手势 / 返回键 — WebView 内点几次链接后,
    先 `goBack()`(BackHandler 接管 `webView.canGoBack()`),栈底回 HomeScreen
13. **后台保活**: WebView 打开一个 SSE 长连接页面(zai MobileAgent 聊天)→
    Home 键切走 App → 等 30 秒以上 → 切回 App → **聊天 session 未断**
    (无重连提示 / 历史消息完整;通知栏有「LAN Agent 后台运行中」 ongoing 通知)
14. **文件上传**: WebView 进 zai → 点「上传图片」 → 系统选择器 → 选一张图 →
    zai 输入框看到缩略图(优先走 `window.lanAgentAttachImages` bridge)
15. **编辑模式**: 点 ✎ → 卡片右侧出现 🗑 + ☰ → 长按拖拽换位 → 点 🗑 删除 →
    点 + 弹 EditCardDialog 加新卡 → 点 ✓ 退出
16. **错误路径**: 关 Wi-Fi / 改错 IP → WebView 静默(ERR_FAILED 已被
    `onReceivedError` 静音,LAN 工具太频繁),InstancesScreen 拉取报错时弹 Snackbar
17. **重置 DataStore**: `adb shell pm clear io.github.hotmanxp.lanagent` →
    启动 App → 回到 5 张 seed 默认卡片(确认改 `Cards.kt` 后这条路径有效)
18. **SSH 启动 zai**: 首屏右上 `>_` 图标 → 加一条 SSH host(name / IP / port / user / password)
    → 保存 → 点「启动 zai」 → 半屏 sheet 显示执行中 → exit code = 0 + 端口可达 →
    自动跳到 Instances 实例管理 WebView;手动关 zai 后再点「启动 zai」也能拉起
19. **SSH 启动失败兜底**: 在 SSH host 配置里把 host 改错 → 「启动 zai」 →
    sheet 显示「connect failed: ...」+ 「查看 /tmp/zai.log」入口(点开有错误日志)
20. **SSH host 持久化 + 编辑 + 删除**: 加完条目,杀进程重开 App,条目还在;
    点条目右侧 ✎ 编辑、🗑 删除(带二次确认)
21. **实例类型 Radio(0.8.0)**: InstancesScreen 右下 `+` 打开新建 Modal →
    「实例类型」Radio 默认选中「标准实例」;手动切到「task-factory」提交 →
    列表里新实例头部 name 旁显示橙色 `task-factory` tag
22. **新建任务工厂实例快捷按钮(0.8.0)**: InstancesScreen 顶栏 `RocketLaunch` 图标 →
    打开新建 Modal 时「实例类型」Radio 默认选中「task-factory」且 `cwd` 预填
    `currentCwd`;用户可手动切回「标准实例」
23. **原生会话列表(0.9.0)**: InstancesScreen 某张卡(有运行端口)→ 动作行点「会话」→
    进原生会话列表(顶栏「会话 + 实例名」),列出该实例 cwd 下的会话
    (标题 + 模型标签 + 相对时间 + sessionId);5s 自动刷新;顶栏 ⟳ 手动刷新;
    空态显示「该实例暂无会话,点上面「新建会话」开始」
24. **新建会话(0.9.0/0.10.0)**: 会话列表顶部「新建会话」大按钮 → 直接进会话详情,
    顶栏标题是「未命名会话」,消息区居中显示**机器人形象 + 「LAN Agent,我帮你」**
25. **原生会话详情 - 历史(0.9.0/0.10.0)**: 点一条已有会话 → 原生渲染完整历史:
    用户消息靠右气泡、助手正文靠左并**按 Markdown 渲染**(标题变大加粗、列表带
    项目符号/序号、`- [x]` 显示勾选框、围栏代码块等宽底框 + 右上角「复制」、
    表格边框 + 可横滚、`>` 引用带左侧竖线、`**粗体**`/`` `行内码` ``/`[链接](url)`
    都按样式渲染)、思考过程折叠卡(默认收起)、工具调用折叠卡(名称 + 状态 chip,
    点开看入参/输出)
26. **原生会话详情 - 实时(0.9.0)**: 在详情页发一条消息 → 底部输入条清空,
    自己的消息立刻出现;助手回复**流式逐字**出现;运行中顶栏右侧出现「运行中」
    标签(空闲时**不显示**),同时输入条右侧变红色停止按钮;回复结束后标签消失。
    中途切到别的 App 再回来,历史不丢
27. **原生会话详情 - 中断/队列(0.9.0)**: 运行中点输入条右侧红色停止按钮 → 状态变「已中断」;
    运行中再发一条 → 输入区上方出现「排队中 (1)」条,可点「插入」(steer)或 ✕ 取消
28. **原生会话详情 - 任务清单(0.9.0)**: 如果这一轮开了 v2 任务,输入区上方出现
    「任务清单 N/M」可折叠条,任务状态随 SSE 实时更新
29. **原生会话详情 - 权限/问询/审核(0.9.0)**: Agent 触发工具权限确认 / AskUserQuestion /
    文档审核时,输入区上方出现对应卡片:权限卡「允许/拒绝」、问询卡选项可点 + 「提交/拒绝」、
    审核卡「批准/驳回」+「查看文件」展开内容。点完卡片消失,Agent 继续跑
30. **原生会话详情 - 在网页打开(0.9.0/0.9.2)**: 点顶栏**副标题**(目录名右侧的 `›`)
    → 弹出会话信息面板 → 点「在网页打开」→ 用 WebView 打开
    `http://host:port/m?sid=<当前会话>`(回到旧路径的兜底入口)
31. **会话页错误路径(0.9.0)**: zai 没以 `--lan` 启动 / 端口不可达 → 会话列表显示
    「加载失败:…」+「请确认实例以 --lan 启动且端口可达」;实例未运行(无端口)时点「会话」→
    Snackbar「实例未运行(无端口),无法查看会话」
32. **输入条形态(0.9.2)**: 会话详情底部是**一个胶囊**包住「波形图标 + 输入框 + 右侧按钮」,
    不再是「输入框 + 框外圆按钮」两个圆角。右侧按钮三态:空输入显示 `+`、
    打字后变成实心圆发送箭头、运行中变成红色实心圆停止
33. **语音输入(0.9.2)**: 点输入条左侧波形图标 → 首次弹录音权限 → 允许后图标变主题色
    并呼吸闪动 → 对着手机说话 → 文字**边说边出现**在输入框里 → 停止说话自动结束。
    拒绝权限 / 设备无识别服务 / 离网时,分别弹明确提示而不是静默失败
34. **图片附件(0.9.2)**: 空输入时点 `+` → 面板「添加图片 / 粘贴剪贴板」→「添加图片」→
    系统相册选择器 → 选一张 → 输入条上方出现缩略图(右上角 X 可删)→ 发送 →
    消息气泡显示「1 张图片」;超过 4 张时弹「最多只能带 4 张图片」。
    (选 PNG 截图 / HEIC 都应该正常 —— 客户端统一转 JPEG)
35. **粘贴剪贴板(0.9.2)**: 先复制一段文字 → `+` →「粘贴剪贴板」→ 文字进输入框;
    剪贴板为空时弹「剪贴板是空的」
36. **会话信息面板(0.9.2)**: 点顶栏副标题 → 底部面板列出 状态 / 目录 / 模型 / 消息数 /
    会话 ID / 实例地址;点会话 ID 那行 → Snackbar「已复制会话 ID」;面板里还有
    「刷新」和「在网页打开」两个动作。顶栏本身只有「返回 + 标题 + 副标题」
37. **整体配色(0.10.0)**: 打开 App —— 页面底是**浅灰**(`#F8F8F8`)而不是跟着壁纸
    变的紫色;顶栏与页面同色(没有白条);卡片 / 填充层是白色并带一圈发丝描边。
    切到系统深色模式:页底变 `#141517`、卡片 `#1F2124`、文字变浅灰,状态栏图标
    自动变白(不会出现白图标压白底)
38. **机器人形象(0.10.0)**: 桌面/抽屉里 App 图标是**青绿底 + 机器人头**;进任意
    空会话 / 清空卡片后的首页空态,能看到机器人形象 + 「LAN Agent,我帮你」
39. **Markdown - 块级(0.10.0)**: 让 Agent 回一段带格式的内容(例如「用 Markdown
    给我一份 5 条带勾选框的清单 + 一个 3 行表格 + 一段 ```bash 代码块 + 一条引用」)
    → 标题明显更大更粗、清单带项目符号/序号、`- [x]` 是勾选图标、表格有边框且
    列多时可左右滑、代码块有语言标签和「复制」按钮(点了能粘出来)、引用左侧有竖线
40. **Markdown - 行内 + 流式(0.10.0)**: `**粗体**` / `*斜体*` / `` `行内代码` `` /
    `~~删除线~~` / `[文字](链接)`(可点,用系统浏览器打开)、裸 `https://…` 也是可点链接;
    助手**流式输出到一半**时代码块已经开始渲染(不会等闭合),半截 `**` 也不会吞掉后面的字
41. **Markdown - 工具卡(0.10.0)**: 展开一个工具调用卡 → 入参/输出的代码块右上角有
    「复制」;思考过程展开后同样按 Markdown 渲染(字号更小、颜色更淡)
42. **用户气泡(0.10.1)**: 发一条消息 → 气泡是**中性浅灰**(不是绿色),
    **四个角一样圆**(右下角没有尖角);长消息能占到接近满宽
43. **输入条(0.10.1)**: 输入区是一张**白色圆角卡、上下两行** —— 上排占位文案是
    「输入消息…」,下排依次是语音图标 / 模型 chip(点开能切模型)/ `+` / 最右的
    灰色**禁用态发送钮**;打一个字 → 发送钮变**青绿**;再清空 → 变回灰色但**按钮不消失**;
    卡下方**没有**「图片 / 粘贴 / 模型 / 更多」那排图标(图片和粘贴在 `+` 面板里)

## 工程位置

`/Users/ethan/code/lan-agent/`(独立 git 仓库,不在 opencc-web monorepo 内)。
