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
18. **SSH 启动 zai**: 首屏右上 `>_` 图标 → 加一条 SSH host(name / IP / port / user / password)
    → 保存 → 点「启动 zai」 → 半屏 sheet 显示执行中 → exit code = 0 + 端口可达 →
    自动跳到 Instances 实例管理 WebView;手动关 zai 后再点「启动 zai」也能拉起
19. **SSH 启动失败兜底**: 在 SSH host 配置里把 host 改错 → 「启动 zai」 →
    sheet 显示「connect failed: ...」+ 「查看 /tmp/zai.log」入口(点开有错误日志)
20. **SSH host 持久化 + 编辑 + 删除**: 加完条目,杀进程重开 App,条目还在;
    点条目右侧 ✎ 编辑、🗑 删除(带二次确认)

## 工程位置

`/Users/ethan/code/lan-agent/`(独立 git 仓库,不在 opencc-web monorepo 内)。
