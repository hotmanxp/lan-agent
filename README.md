# LAN Agent

简单 Android App,把局域网内多个 opencc-web 实例的入口收成卡片列表,
点击卡片进入 WebView 详情加载对应 URL(默认目标是 `/m` 移动 Agent 页面)。

## 改入口

有 3 种方式,**推荐在 APP 内完成**:

1. **APP 内编辑模式** — 首屏右上 `✎` 进编辑模式,长按拖拽换位,点 `🗑` 删除,
   点 `+` 加新卡。改完即时写 DataStore,下次启动还是这些。
2. **QR 扫码添加** — `+` → 选 QR 扫码,扫 opencc-web 实例管理页导出的二维码即可
   (CameraX + ML Kit Barcode)。
3. **改 seed 卡片** — 编辑 [`app/src/main/java/io/github/hotmanxp/lanagent/data/Cards.kt`](app/src/main/java/io/github/hotmanxp/lanagent/data/Cards.kt)
   里的 `defaultCards` 列表,改完 `./gradlew :app:installDebug` 重装即可。
   **只影响卸载重装后的首次启动**(已有数据从 DataStore 读)。

如目标 IP 不在白名单,还要编辑
[`app/src/main/res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml)
加一行 `<domain includeSubdomains="true">你的.IP</domain>`。

## 0.6.0 新增功能

相比 0.1.x 单卡片模式,0.6.0 引入多实例管理:

- **多实例列表** — InstancesScreen 替代 HomeScreen 单卡片模式,支持任意多个
  opencc-web 实例(名称 / 地址 / 端口 / 备注)
- **三种添加方式** — 手动表单 / 选目录拉 `/instances` / **QR 扫码**
  (CameraX 1.3.4 + ML Kit Barcode)
- **编辑 / 删除** — 实例卡片长按进编辑模式,可改端口、改备注、删除
- **WebView 文件上传** — `<input type="file">` 通过系统图库选择器(content:// URI),
  支持 Android 13+ 的 `READ_MEDIA_IMAGES`
- **前台 Service 保活** — `service/WebViewKeepAliveService`(dataSync foreground
  service),让 WebView 的 SSE / WebSocket / long-poll 在 Activity onPause 后仍保活,
  避免用户切走再回来时 session 断开

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

确认桌面浏览器能访问 `http://<本机 IP>:8101/m` 看到 MobileAgent 页。

1. **服务端就绪**:`http://<本机 IP>:8101/m` 在桌面浏览器可访问
2. **编译**: `./gradlew :app:assembleDebug` 出 APK 无报错
3. **安装**: `./gradlew :app:installDebug` 装到 Android 真机(API 26+)
4. **冷启动**: App 启动看到 TopAppBar "LAN Agent" + 三张卡片,色条 / 标题 / 副标题正确
5. **导航**: 点任一卡片 → 进 WebView 详情,TopAppBar 显示对应 URL
6. **WebView 渲染**: 能看到 opencc-web MobileAgent 页面(列表、输入框、抽屉)
7. **刷新**: 点 TopBar 刷新按钮 → WebView 重载,URL 不变
8. **返回栈**: 系统返回手势 / TopBar 返回箭头 — WebView 内点几次链接后,
   先 `goBack()`,栈底回 HomeScreen
9. **错误路径**: 关 Wi-Fi / 改错 IP → 触发 Snackbar 报错,可点刷新重试
10. **白名单外**: 把 `defaultCards[0].url` 改成 `http://8.8.8.8/m` 重编 → WebView
    加载 ERR_FAILED + Snackbar 提示(回归测试)

## 工程位置

`/Users/ethan/code/lan-agent/`(独立 git 仓库,不在 opencc-web monorepo 内)。
