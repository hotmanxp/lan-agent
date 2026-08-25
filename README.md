# LAN Agent

简单 Android App,把局域网内多个 opencc-web 实例的入口收成卡片列表,
点击卡片进入 WebView 详情加载对应 URL(默认目标是 `/m` 移动 Agent 页面)。

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
  选手动表单 / 目录选择器(拉 `/api/fs/picker`)/ QR 扫码。**新加的是服务端
  实例定义,跟首页 Card 列表是两套数据**。

如目标 IP 不在白名单,还要编辑
[`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml)
加一行 `<domain includeSubdomains="true">你的.IP</domain>`。

## 0.6.0 新增功能

相比 0.1.x 单卡片模式,0.6.0 在保留 HomeScreen 卡片列表的同时新增**原生实例管理屏**:

- **原生实例管理** — 首屏 Storage 按钮 → InstancesScreen 拉 `/api/instances`,
  2.5s 轮询;InstanceCard 对标 web Instances.tsx(状态 Tag / LAN Switch / 内核 Tag
  / 启动端口 / 运行端口 / cwd / PID / 启动时间 / 运行时长 / 最后心跳 / 错误)
- **五种动作** — 启动 / 停止 / 重启 / 删除(带二次确认)/ 打开(直接跳 WebView)
- **三种创建方式** — 手动表单(name / cwd / LAN / 端口模式 / 内核)/ 目录选择器
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
   看到轮询卡片列表(状态 Tag、LAN Switch、内核 Tag、启动端口、运行端口、
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

## 工程位置

`/Users/ethan/code/lan-agent/`(独立 git 仓库,不在 opencc-web monorepo 内)。
