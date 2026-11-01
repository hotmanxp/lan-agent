# 手机能力层：DSH-Android 提供了什么，我们差在哪

> 调研对象：`jackwener/DSH-Android`（HEAD `c6f3b55e`）。
> 取数方式：GitHub API 拉 `app/src/main/java/io/github/jackwener/dsh/core/capability/` 全量源码，
> 从 `Tool(...)` 声明里解析出工具名 / 分组 / 风险级别 / 解锁要求 / 描述。

## 一、结论先说

DSH 的手机能力层是 **66 个工具 / 10 个分组**，全部由一个 `CapabilityHub` 统一把守，
通过 socketpair 能力通道暴露给引擎（Node 侧是 `@dsh-android/plugin-device` 插件）。

我们（lan-agent 本机引擎）现在是 **6 个工具**（`mcp__phone-bridge__phone_*`）：
截屏、通知列表、读剪贴板、启动应用、列应用、读写系统设置。

差距最大的两块是 **屏幕操作**（读屏 / 点击 / 输入 / 滑动 —— DSH 靠无障碍服务实现）
和 **文件/通讯/系统细粒度操作**。

## 二、DSH 的能力层架构

| 环节 | DSH 怎么做 |
|---|---|
| 工具定义 | 每个工具一个 `Tool(name, ToolGroup.X, Risk.Y, needsUnlock, desc, schema)`，分组 / 风险级别是**声明式**的 |
| 统一入口 | `CapabilityHub` —— 「device capabilities 的单一闸门」，所有调用过它 |
| 通道 | socketpair（`DSH_BRIDGE_FD`），JSON-RPC：`capability/list` / `capability/invoke` / `capability/cancel`；App → Node 还有 `capability/changed`（工具组开关变了） |
| 审批 | `risk` 映射到引擎侧批准要求：`read` 自动放行，`write` 询问，`sensitive` / `dangerous` 更严 |
| 超时 | 每个工具有自己的 `timeoutMs`；插件按 `timeoutMs + 15s` 注册，调用超时发 `capability/cancel` |
| 按会话收窄 | `deviceGroups` —— 每个会话能只开放某几个工具组 |
| 兜底 | 没有权限时走 **Shizuku**（`settings put`、Wi-Fi/蓝牙/移动数据开关、后台剪贴板），Shizuku 不可用才回退到 UI 面板 |

⚠️ 它的能力通道是 **socketpair + JSON-RPC**，因为它的引擎是 C++ 的 dsh。
我们这边引擎是 Node，HTTP 更现成 —— 所以我们走 `127.0.0.1:9421` 的 `CapabilityService`，
协议形态不同，但**可以照抄它的工具分组 / 风险分级 / 超时取消这套设计**。

## 三、66 个工具（按分组）

### Screen（10）—— 无障碍驱动的真·手机操作
`screen_read`（读屏，返回 `[e12] Type "text" #resource-id @x,y` 这种带 id 的元素列表）·
`screen_capture`（截图，MediaProjection）· `screen_tap`（按元素 id / 可见文字 / 坐标点）·
`screen_type`（输入到指定字段）· `screen_swipe`（拖拽）· `screen_scroll` ·
`screen_key`（按键）· `screen_wait`（等某文字出现 / 消失）· `screen_set_wheel`（滚轮选择器）·
`hand_to_user`（把只能人做的步骤交回用户：登录、验证码、支付确认）

> 这组是 DSH 的**核心竞争力**：agent 能真正「看着屏幕点」。需要 AccessibilityService。

### Apps（8）
`app_list`（可启动应用列表）· `app_info`（版本 / 安装时间 / 体积 / 导出的 activity）·
`app_launch`（按包名打开）· `app_open_link`（https/tel/geo/mailto/各种 scheme）·
`app_send_intent`（显式 Intent，带 extras）· `share_to_app`（分享文件/文本给别的应用）·
`app_guide`（这个 App 的「使用经验」——之前几次跑下来记下的 UI 事实）· `app_note`（写这条经验）

### Files（10）—— 共享存储
`file_read`（文本文件，GBK/GB18030 自动识别）· `file_write` · `file_list` · `file_mkdir` ·
`file_move`（重命名/移动）· `file_delete`（危险级，删前列清单给用户确认）·
`file_stat` · `file_search`（按名字/内容搜共享存储，ripgrep）·
`archive_create` / `archive_extract`（zip）

### System（14）
`device_status`（电量 / 充电 / 网络 / Wi-Fi SSID / 存储 / 勿扰 / 各音量流）·
`wifi_toggle` · `mobile_data_toggle` · `bluetooth_toggle` · `flashlight` ·
`brightness_set` · `volume_set` · `sound_mode` · `dnd_set`（勿扰）·
`alarm_set` / `alarm_show`（闹钟/倒计时）· `location_get`（经纬度 + 精度 + 街道地址）·
`setting_get` / `setting_put`（`settings put`，需 Shizuku）

### Comms（7）
`calendar_read` / `calendar_add` / `calendar_new_event` · `sms_read` / `sms_send` ·
`contacts_search` · `call_log_read`

### Notifications（4）
`notification_list`（含消息正文、快捷回复按钮、key）· `notification_dismiss` ·
`notification_open`（像点通知那样打开，或点它的动作按钮）· `notification_reply`（走通知的 inline reply）

### Browser（7）—— **自带一个浏览器**
`browser_open`（打开网页并返回文本 + 带编号的可交互元素）· `browser_read` · `browser_click` ·
`browser_type` · `browser_scroll` · `browser_screenshot` · `browser_back`

### Rules（3）—— 自动化
`rule_list` / `rule_update` / `rule_delete`：条件触发的事件自动化
（`RuleMonitorService` + `TriggerReceiver` + 开机自启 + 悬浮球）。

### Clipboard（2）· Media（1）
`clipboard_get` / `clipboard_set` · `media_control`（控制正在播放的东西 / 查当前在放什么）

## 四、它依赖的 Android 基础设施与权限

**服务**：`DshAccessibilityService`（读屏 / 点击 / 输入）· `DshNotificationListener`（通知）·
`DshInputMethodService`（输入法侧输入）· `DshVoiceInteractionService` / `DshRecognitionService`（语音）·
`QuickTileService`（快捷设置磁贴）· `FloatingBallService`（悬浮球）·
`RuleMonitorService` + `TriggerReceiver` / `BootReceiver`（自动化）· `EngineService`（引擎宿主）

**权限**（挑重点）：`MANAGE_EXTERNAL_STORAGE`（共享存储）· `QUERY_ALL_PACKAGES`（列应用）·
`WRITE_SETTINGS` · `SYSTEM_ALERT_WINDOW`（悬浮球）· `READ_CONTACTS` / `READ_SMS` / `SEND_SMS` /
`READ_CALL_LOG` / `READ_CALENDAR` / `WRITE_CALENDAR` · `ACCESS_*_LOCATION` ·
`BLUETOOTH_CONNECT` · `RECORD_AUDIO` · `SET_ALARM` · `SCHEDULE_EXACT_ALARM` · `POST_NOTIFICATIONS`

**Shizuku**（可选但很强）：没有它，Wi-Fi/蓝牙/移动数据开关只能「打开设置面板让用户自己点」，
`settings put` 完全做不了，剪贴板在前台以外也读不到。

## 五、我们的现状与差距

### 现在有的（6 个，`tools/engine-mods/phone-bridge/index.mjs`）
| 工具 | 覆盖 DSH 的 |
|---|---|
| `phone_screenshot` | `screen_capture`（但没有 `screen_read` 的元素列表） |
| `phone_notifications` | `notification_list`（不能点开 / 不能回复 / 不能清除） |
| `phone_clipboard` | `clipboard_get`（不能写） |
| `phone_open_app` | `app_launch`（不能拉起指定 activity / 不能 deep link / 不能分享） |
| `phone_list_apps` | `app_list` |
| `phone_settings` | `setting_get/put` 的一部分（brightness / screen_timeout / airplane_mode / volume / ringer_mode / wifi） |

### 差距（按「值不值得补」排序）

| 优先级 | 想补的 | DSH 对应 | 前置条件 | 难度 |
|---|---|---|---|---|
| **P0** | **读屏 + 点击 + 输入** | `screen_read` / `screen_tap` / `screen_type` | AccessibilityService（读节点树）+ MediaProjection（截屏已有） | 中——但这是质变：agent 能真正操作 App |
| **P0** | 通知点开 / 回复 / 清除 | `notification_open` / `notification_reply` / `notification_dismiss` | 已有 NotificationListenerService | 低 |
| **P1** | 系统开关补齐（蓝牙 / 移动数据 / 手电 / 勿扰 / 闹钟） | `bluetooth_toggle` 等 | 无（部分要 Shizuku 才能直接切） | 低 |
| **P1** | deep link / 指定 activity / 分享给别的 App | `app_open_link` / `app_send_intent` / `share_to_app` | 无 | 低 |
| **P1** | 定位 | `location_get` | ACCESS_*_LOCATION | 低 |
| **P2** | 共享存储文件读写 | `file_*`（10 个） | MANAGE_EXTERNAL_STORAGE（敏感） | 中 |
| **P2** | 自带浏览器 | `browser_*`（7 个） | WebView（我们有现成的） | 中 |
| **P2** | 短信 / 通讯录 / 通话记录 / 日历 | `sms_*` / `contacts_*` / `call_log_read` / `calendar_*` | 一堆敏感权限 | 低（但权限面很大） |
| **P3** | 自动化规则（条件触发） | `rule_*` | 常驻服务 + 开机自启 + 悬浮球 | 高 |

### 值得抄的三条「设计」，不抄代码

1. **工具声明带 risk 分级**（`read` / `write` / `sensitive` / `dangerous`）+ 引擎侧审批映射
   —— 我们现在是「一律 bypassPermissions」（手机上没有批准界面），但**声明**可以先有：
   `dangerous` 的工具在提示词里就别鼓励模型乱用。
2. **每个工具带 `timeoutMs` + 超时发 cancel** —— 手机上引擎进程随时被杀，长工具调用必须有界。
3. **`capability/changed`：工具集变了通知引擎** —— 我们现在是 mod 静态注册，加工具就得重启引擎。

## 六、抄的具体做法

我们这边引擎是 Node，**不抄 socketpair**，沿用 HTTP：

```
模型 → ctx.registerTool 的 execute() → POST http://127.0.0.1:9421/cap/<name> → Kotlin → Android API
```

要加一个能力，三步：

1. `engine/PhoneCapabilities.kt` 里加一个能力分支（返回 JSON）；
2. `engine/CapabilityService.kt` 的路由表里加一条；
3. `tools/engine-mods/phone-bridge/index.mjs` 里 `ctx.registerTool` 一个同名工具。

⚠️ mod 目录在**引擎包里**（34MB 下载）—— 改 mod 要重打引擎包、用户重下。
如果某个能力要频繁迭代，考虑把它放进 APK assets、起引擎时拷进 `OPENCC_MODS_DIR`
（跟手机端 agent 配置一个路子：`EngineAgentProfile.apply()`）。
