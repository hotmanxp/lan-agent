// ui/FileViewerOverlay.kt — 从右侧滑入的全屏文件预览层(DisplayFiles 卡片点进来)。
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
// 数据来源:`GET {baseUrl}/api/fs/preview?path=`(见 data/AgentApi.kt 的
// [AgentApi.previewFile])。响应按 kind 分岔 —— image 是 base64、text/html 是
// 原文、binary 只有元数据(见 data/DisplayFiles.kt 的 [FilePreview])。
package io.github.hotmanxp.lanagent.ui

import android.util.Base64
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
import io.github.hotmanxp.lanagent.service.WebViewFactory
import kotlinx.coroutines.launch

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
                    ImageBody(path = path, preview = p)

                // HTML 与 SVG 都交给 WebView:SVG 是 XML,BitmapFactory 解不了,
                // 而 WebView 天生会渲染它。
                p.fileKind == FileKind.Html || (p.fileKind == FileKind.Image && isSvg(path)) ->
                    HtmlBody(html = p.content.orEmpty())

                p.fileKind == FileKind.Text -> TextBody(path = path, content = p.content.orEmpty())

                else -> BinaryBody(preview = p, onReveal = { reveal() })
            }
        }
    }
}

private fun isSvg(path: String): Boolean = path.lowercase().endsWith(".svg")

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
 * 这里**不采样**:服务端已经把图片卡在 1 MiB,而用户主动点开就是要看清楚,
 * 降采样反而丢失细节。缩略图那侧才必须采样(见 `RemoteImageThumb`)。
 */
@Composable
private fun ImageBody(path: String, preview: FilePreview) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = decodeBase64Image(preview.content)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

private fun decodeBase64Image(encoded: String?): android.graphics.Bitmap? {
    if (encoded.isNullOrEmpty()) return null
    return runCatching {
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}

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
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
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

/** binary:不内联预览 —— 给元数据 + 「在 Mac 上打开所在目录」。 */
@Composable
private fun BinaryBody(preview: FilePreview, onReveal: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = "此文件类型不支持内联预览",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            val meta = listOfNotNull(
                preview.ext?.takeIf { it.isNotBlank() },
                preview.size.takeIf { it > 0 }?.let { formatBytes(it) },
            ).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
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
