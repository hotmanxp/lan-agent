// data/FileUpload.kt — 「文件」附件通道:选文档 → 读字节 → base64 → 上传副本
// → 拿回绝对路径,**作为纯文本内嵌进用户消息**发给模型。
//
// 走的是服务端既有的 `POST /api/fs/upload`(opencc-web `fs.ts:657`),不是
// 图片那条 `contentBlocks` 路。web 端拖文件进来就是这条路
// (`AgentInputBox.tsx:925-975`),手机端照抄,服务端一行不用改。
//
// **为什么是路径而不是 document content block**:zai 的 PromptRequest 只收
// image / text 两种块(`agent.ts:234-237`);就算加上 document 块,OpenAI 系
// provider 的 `convertUserContent`(`openaiClient.ts:122-149`)会把非
// image/text 的块**静默丢弃** —— 不报错、不 400,模型就是收不到文件。
// 路径方案对 provider 完全无感:模型拿自己的 Read 工具去读那个绝对路径。
package io.github.hotmanxp.lanagent.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `POST /api/fs/upload` 响应(`fs.ts:704-711` `FsUploadResult`)。
 *
 * `relPath` 服务端已与 `absPath` 同值(上传目录迁出 cwd 那次改动),这里保留
 * 字段只为跟 wire 对齐,不用它。
 */
@Serializable
data class FsUploadResult(
    val ok: Boolean = false,
    val error: String? = null,
    val absPath: String? = null,
    val relPath: String? = null,
    val name: String? = null,
    val size: Long = 0L,
)

/**
 * 选中的文件(只带元信息,不带内容)。
 *
 * `byteSize` 是 provider 声明的大小,`-1` = 未知 —— 仅用于展示,请求体的
 * `contentLength()` 拿不到时会退回 chunked 编码,不影响上传。
 */
data class PickedFile(
    val name: String,
    val uri: Uri,
    val byteSize: Long,
) {
    /** 给用户看的大小,如 "312 KB"。未知时返回 null(不编一个假大小)。 */
    val sizeLabel: String? get() = formatSizeLabel(byteSize)
}

/**
 * 字节数 → 人话大小。**顶层纯函数而不是 [PickedFile] 上的计算属性**,
 * 是为了能在纯 JVM 单测里直接调 —— 构造 [PickedFile] 得先造一个
 * `Uri`,而 `Uri.parse` 在没有 Robolectric 的单测里是 stub,一碰就
 * `ExceptionInInitializerError`(连整个 test class 都建不起来)。
 */
internal fun formatSizeLabel(bytes: Long): String? = when {
    bytes < 0 -> null
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024f / 1024f / 1024f)
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    else -> "${bytes / 1024} KB"
}

object FileUploads {

    /**
     * 读一个 `content://` 文档 Uri 的元信息(文件名 + 声明大小)。
     *
     * **只取元信息,不读字节** —— 字节在 [uploadFile] 里由 OkHttp 的
     * [okhttp3.RequestBody.writeTo] 直接从 InputStream 边读边写 socket。
     * 内存占用是 64 KB 一个块,跟文件多大无关。
     */
    suspend fun meta(context: Context, uri: Uri): PickedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val m = queryMeta(resolver, uri)
        PickedFile(
            // 清洗失败(空名 / 全是控制符)时退回 "file" —— 服务端
            // sanitizeUploadName 也会拒,与其等 400 不如本地兜个能用的名字。
            name = sanitizeFileName(m.name) ?: "file",
            uri = uri,
            byteSize = m.size ?: -1L,
        )
    }

    private data class Meta(val name: String?, val size: Long?)

    private fun queryMeta(resolver: ContentResolver, uri: Uri): Meta = runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (!cursor.moveToFirst()) return@use Meta(null, null)
            Meta(
                name = nameIdx.takeIf { it >= 0 }?.let { cursor.getString(it) },
                // 目录 / 虚拟 provider 常给 -1,当「未知」处理。
                size = sizeIdx.takeIf { it >= 0 }?.let { cursor.getLong(it) }?.takeIf { it >= 0 },
            )
        }
    }.getOrNull() ?: Meta(null, null)
}

/**
 * 文件名清洗,对齐服务端 `fs.ts:618 sanitizeUploadName`。
 *
 * 剥目录分量(防穿越,虽然落盘目录是固定的 `~/.zai/uploads/`)、拒空名 / `.` /
 * `..` / 超 200 字 / 控制符。控制符那条的理由服务端注释写了:绝对路径后面要
 * 被粘进聊天消息,控制符在里面是有歧义的。
 */
internal fun sanitizeFileName(raw: String?): String? {
    if (raw == null) return null
    val cleaned = raw.substringAfterLast('/').substringAfterLast('\\').trim()
    if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
    if (cleaned.length > 200) return null
    if (cleaned.any { it.code < 0x20 || it.code == 0x7f }) return null
    return cleaned
}

/** 上传请求头里的文件名。URL-encode 后服务端 decodeURIComponent 还原。 */
internal fun uploadFileNameHeader(name: String): String =
    java.net.URLEncoder.encode(name, "UTF-8")
        // `URLEncoder` 默认把空格编成 `+`(那是 query 的规矩)。header 里
        // `+` 不会被 decode 成空格 —— decodeURIComponent 把它当字面 `+`。
        // 换成 %20 与服务端 `decodeURIComponent` 对齐。
        .replace("+", "%20")

/**
 * 把上传拿到的绝对路径并进输入框。
 *
 * 与剪贴板粘贴(`AgentSessionScreen.pasteFromClipboard`)同一个约定:空输入直接
 * 放进去,已有内容另起一行 —— 不然会和末尾文字黏成一个词。
 */
internal fun mergeIntoInput(current: String, path: String): String =
    if (current.isBlank()) path else "${current.trimEnd()}\n$path"
