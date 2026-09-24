// ui/FileViewerOverlay.kt — 从右侧滑入的全屏文件预览层(PresentFile 卡片点进来)。
//
// 为什么是 overlay 而不是独立路由:
//   - 预览是「叠在会话上的一层」,不是一次导航。走路由会往返回栈里塞一层,
//     系统返回键要先把这层弹掉才回得到会话,而且详情页路由匹配不到任何 tab,
//     底栏高亮会跟着错位。
//   - 会话的 transcript / 输入框 / SSE 状态全程不卸载,关掉就回到原样。
//
// 为什么是全屏 overlay 而不是就地展开在消息流里:
//   - **HTML** 只能靠 WebView 渲染,而 WebView 塞进 LazyColumn 的某个 item 里
//     既贵(每个卡片一个 Chromium 实例)又脆(测量时机、回收时机都不可控)。
//   - **大图**需要全屏 + 黑底 + Fit 才看得清(跟用户消息的
//     [FullScreenImageViewer] 同款处理)。
//   - 顺带把「在 Mac 上打开所在目录」(`POST /api/fs/reveal`)收在这一层,
//     文件卡片本身就不必再挂一个次要动作。
//
// data 来源:`GET {baseUrl}/api/fs/preview?path=`(见 data/AgentApi.kt 的
// [AgentApi.previewFile]),**图片的字节另走 `GET /api/fs/raw`**
// ([AgentApi.rawFile],≤ 10 MiB 原始流)—— 2026-09-24 起 `/api/fs/preview` 对
// 超过 1 MiB 的图片只回元数据,base64 那条路已经装不下大图。
// 响应按 kind 分岔 —— text/html 是原文、文档类与 binary 只有元数据
// (见 data/PresentFile.kt 的 [FilePreview])。
package io.github.hotmanxp.lanagent.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import io.github.hotmanxp.lanagent.data.AgentApi
import io.github.hotmanxp.lanagent.data.FileKind
import io.github.hotmanxp.lanagent.data.FilePreview
import io.github.hotmanxp.lanagent.data.fileKindLabel
import io.github.hotmanxp.lanagent.data.isDocument
import io.github.hotmanxp.lanagent.service.WebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * 一次预览的目标。
 *
 * @param baseUrl 该文件所在**实例**的 baseUrl(不是文件所在主机的路径 ——
 *   文件系统在 Mac 上,预览请求发给实例进程,由它去读盘)。
 * @param path 文件的绝对路径(服务端 `GET /api/fs/preview` 的 `path` 参数)。
 */
data class FilePreviewTarget(val baseUrl: String, val path: String)

/**
 * 从右侧滑入的全屏预览层。宿主(`AgentSessionPane`)只要把它叠在内容之上、
 * 用 state 控制开合即可 —— 它自己不占路由,不参与返回栈。
 *
 * @param visible 开合状态。
 * @param target 当前(或**最近一次**)预览目标。关的时候不要把这里置空 ——
 *   滑出动画期间内容还得在场,置空会让抽屉在滑走的过程中变成一片空白。
 *   null 表示还没预览过任何文件,此时即使 [visible] 为 true 也不渲染。
 * @param onClose 点 ✕ 关闭(系统返回键由宿主接 `BackHandler`)。
 */
@Composable
fun FileViewerOverlay(
    visible: Boolean,
    target: FilePreviewTarget?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && target != null,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier,
    ) {
        // 退出动画期间 visible 已是 false 而 target 仍在,内容照常渲染。
        target?.let {
            FileViewerContent(baseUrl = it.baseUrl, path = it.path, onClose = onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewerContent(
    baseUrl: String,
    path: String,
    onClose: () -> Unit,
) {
    val api = remember(baseUrl) { AgentApi(baseUrl) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var loading by remember(path) { mutableStateOf(true) }
    var preview by remember(path) { mutableStateOf<FilePreview?>(null) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    var reloadTick by remember(path) { mutableStateOf(0) }

    LaunchedEffect(api, path, reloadTick) {
        loading = true
        error = null
        // 上一份内容先清掉:换文件时若渲染层还留着旧 bitmap / 旧 WebView 内容,
        // 会先闪一下上一个文件。
        preview = null
        runCatching { api.previewFile(path) }.fold(
            onSuccess = { preview = it; loading = false },
            onFailure = { error = previewErrorMessage(it); loading = false },
        )
    }

    fun reveal() {
        scope.launch {
            val ok = runCatching { api.revealFile(path) }.getOrDefault(false)
            snackbarHostState.showSnackbar(
                if (ok) "已在 Mac 上打开所在目录" else "打开目录失败（Mac 可能没起图形界面）"
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "关闭预览",
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            text = path.trimEnd('/').substringAfterLast('/'),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = path,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reloadTick++ }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "重新加载",
                        )
                    }
                    IconButton(onClick = { reveal() }) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = "在 Mac 上打开所在目录",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val p = preview
            when {
                loading -> CenterSpinner()

                error != null -> CenterMessage(
                    text = error.orEmpty(),
                    onRetry = { reloadTick++ },
                )

                p == null -> CenterMessage(text = "没有内容", onRetry = null)

                p.fileKind == FileKind.Image && !isSvg(path) ->
                    ImageBytesBody(api = api, path = path)

                // SVG 是 XML,BitmapFactory 解不了 → 交给 WebView,而且**直接吃
                // /api/fs/raw 的字节流**,不受 /api/fs/preview 的 1 MiB 限制。
                p.fileKind == FileKind.Image -> WebBody(url = rawUrl(baseUrl, path))

                p.fileKind == FileKind.Html -> HtmlBody(html = p.content.orEmpty())

                p.fileKind == FileKind.Text -> TextBody(path = path, content = p.content.orEmpty())

                // 文档类(docx/sheet/ppt/pdf/legacy-office):手机端没有渲染器,
                // 老实说清楚,并把「在 Mac 上打开」放在最显眼处。
                p.fileKind.isDocument -> DocumentBody(preview = p, onReveal = { reveal() })

                else -> BinaryBody(preview = p, onReveal = { reveal() })
            }
        }
    }
}

private fun isSvg(path: String): Boolean = path.lowercase().endsWith(".svg")

/** `/api/fs/raw` 的地址(图片 / 矢量图在 WebView 里直接开)。 */
private fun rawUrl(baseUrl: String, path: String): String =
    "${baseUrl.trimEnd('/')}/api/fs/raw?path=${URLEncoder.encode(path, "UTF-8")}"

@Composable
private fun CenterSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenterMessage(text: String, onRetry: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("重试", fontSize = 13.sp) }
            }
        }
    }
}

/**
 * 图片:黑底 + `ContentScale.Fit`。不做缩放交互,但保持与用户消息
 * 全屏查看器([FullScreenImageViewer])一致的观感。
 *
 * 字节走 `GET /api/fs/raw`(≤ 10 MiB 原始流)—— `/api/fs/preview` 的 base64
 * 只覆盖 ≤ 1 MiB 的图,2026-09-24 起大图那边根本拿不到内容(只回元数据)。
 *
 * **必须采样**:全屏是手机上的大图入口,但 10 MiB 的上限意味着可能有
 * 8000×8000 的图 —— 原样解码是 256 MB,必 OOM。采样到
 * [FULL_IMAGE_MAX_EDGE](约 2.5 倍于主流手机屏宽)肉眼看不出差别。
 */
@Composable
private fun ImageBytesBody(api: AgentApi, path: String) {
    val state by produceState<ImageState>(ImageState.Loading, api, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                decodeSampled(api.rawFile(path), maxEdge = FULL_IMAGE_MAX_EDGE)
                    ?: throw IllegalStateException("图片解码失败")
            }.fold(
                onSuccess = { ImageState.Ok(it) },
                onFailure = { ImageState.Failed(previewErrorMessage(it)) },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            is ImageState.Loading -> CircularProgressIndicator(color = Color.White)

            is ImageState.Ok -> Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            is ImageState.Failed -> Text(
                text = s.message,
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

private sealed interface ImageState {
    data object Loading : ImageState
    data class Ok(val bitmap: android.graphics.Bitmap) : ImageState
    data class Failed(val message: String) : ImageState
}

/** 全屏图片的采样目标边长(见 [ImageBytesBody])。 */
private const val FULL_IMAGE_MAX_EDGE = 2560

/**
 * 一段 HTML 字符串 → WebView。
 *
 * 两个刻意的选择:
 *   1. **`shouldOverrideUrlLoading` 一律返回 true** —— 这是只读预览,点链接
 *      跳走会让用户莫名其妙地离开文件(而且没有地址栏可以回来)。子资源
 *      (CSS / 图片 / fetch)不受影响,那本来就不是主框架导航。
 *   2. **`doOnLayout` 里才 load** —— 与 SSH 终端踩过的坑同源:factory 那一刻
 *      WebView 还没被测量,尺寸 0×0,`html{height:100%}` 会解析成 0,渲染成
 *      一片空白(详见 AGENTS.md「WebView 在 Compose 里全黑」)。
 */
@Composable
private fun HtmlBody(html: String) {
    LazyWebView { it.loadDataWithBaseURL(null, html, "text/html", "utf-8", null) }
}

/** 直接开一个 URL(SVG 走 `/api/fs/raw`)。 */
@Composable
private fun WebBody(url: String) {
    LazyWebView { it.loadUrl(url) }
}

@Composable
private fun LazyWebView(load: (WebView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebViewFactory.createForContent(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                    ): Boolean = true
                }
                var loaded = false
                doOnLayout { v ->
                    if (!loaded && v.height > 0) {
                        loaded = true
                        load(v as WebView)
                    }
                }
            }
        },
        onRelease = { webView: WebView -> webView.destroy() },
    )
}

/** 文本 / Markdown。`.md` 走自研 Markdown,其余当纯文本等宽显示。 */
@Composable
private fun TextBody(path: String, content: String) {
    val isMarkdown = path.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        if (isMarkdown) {
            MarkdownText(content)
        } else {
            CodeBox(content)
        }
    }
}

/**
 * 文档类(docx / sheet / ppt / pdf / legacy-office)。
 *
 * 手机端**没有**文档渲染器(web 端那套是 JSZip + PDF.js + SheetJS,一整套浏览器
 * 库),所以这里不假装能预览 —— 说清楚类型,并把「在 Mac 上打开所在目录」放在
 * 最显眼的位置。对齐 web 端「文档类只给类型说明 + ↗」的取舍。
 */
@Composable
private fun DocumentBody(preview: FilePreview, onReveal: () -> Unit) {
    NoticeBody(
        title = fileKindLabel(preview.fileKind),
        detail = listOfNotNull(
            preview.ext?.takeIf { it.isNotBlank() },
            preview.size.takeIf { it > 0L }?.let { formatBytes(it) },
            "手机端不支持预览",
        ).joinToString(" · "),
        onReveal = onReveal,
    )
}

/** binary:不内联预览 —— 给元数据 + 「在 Mac 上打开所在目录」。 */
@Composable
private fun BinaryBody(preview: FilePreview, onReveal: () -> Unit) {
    NoticeBody(
        title = "此文件类型不支持内联预览",
        detail = listOfNotNull(
            preview.ext?.takeIf { it.isNotBlank() },
            preview.size.takeIf { it > 0L }?.let { formatBytes(it) },
        ).joinToString(" · "),
        onReveal = onReveal,
    )
}

/** 「不支持预览」家族共用的居中版式:标题 + 元数据 + 打开目录。 */
@Composable
private fun NoticeBody(title: String, detail: String, onReveal: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = detail,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable(onClick = onReveal),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = "在 Mac 上打开所在目录", fontSize = 13.sp)
                }
            }
        }
    }
}
