// data/InstancesApi.kt — zai 实例管理 / fs picker 的 HTTP 客户端。
//
// baseUrl 形如 "http://192.168.101.69:9201",由 HomeScreen 从卡片列表里识别
// "指向 /instances 的卡片"派生(见 data/Cards.kt 的 findManagerBaseUrl)。
//
// 全部方法 suspend,失败抛 IOException / HttpException,UI 层 catch。
package io.github.hotmanxp.lanagent.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** PATCH 三态:`Unset`=不发该字段;`Null`=显式清除回 inherit/auto;`Set(value)`=持久化该值。 */
sealed interface PatchValue<out T> {
    object Unset : PatchValue<Nothing>
    object Null : PatchValue<Nothing>
    data class Set<T>(val value: T) : PatchValue<T>
}

fun <T> patchOf(value: T?): PatchValue<T> = if (value == null) PatchValue.Null else PatchValue.Set(value)

class InstancesApi(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 生命周期动作(启动/停止/重启)专用 client —— 读超时必须比 [client] 长。
     *
     * 服务端的 restart = `doStop` + `doStart`(opencc-web
     * `instanceSupervisor.ts:511`),而 `doStop` 对不理 SIGINT 的子进程会
     * **等满 STOP_TIMEOUT_MS(10s) 再 SIGKILL,再等 1.5s grace**(见同文件
     * `doStop`)。也就是说一次「不听话的实例」的重启,响应要 11.5s+ 才回来 ——
     * 用 10s 的 readTimeout 会**先在客户端超时**,UI 报「失败」而实例其实正在重启。
     */
    private val actionClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun urlFor(path: String): String {
        val base = baseUrl.trimEnd('/')
        val normalized = if (path.startsWith("/")) path else "/$path"
        return "$base$normalized"
    }

    private fun parseErrorBody(body: String): String =
        runCatching {
            val obj = json.parseToJsonElement(body) as? JsonObject
            (obj?.get("error") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.getOrNull() ?: body.take(200)

    private suspend inline fun <reified T> execute(req: Request, http: OkHttpClient = client): T =
        withContext(Dispatchers.IO) {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw HttpException(resp.code, parseErrorBody(resp.peekBody(2048).string()))
                }
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) {
                    @Suppress("UNCHECKED_CAST")
                    return@use Unit as T
                }
                json.decodeFromString<T>(raw)
            }
        }

    private suspend fun executeObject(
        req: Request,
        key: String,
        http: OkHttpClient = client,
    ): JsonObject {
        val wrapper = execute<JsonObject>(req, http)
        val obj = wrapper[key] as? JsonObject
            ?: throw IOException("missing '$key' (object) in response: ${wrapper.keys}")
        return obj
    }

    // ===== instances =====

    suspend fun listInstances(): List<InstanceSnapshot> {
        val req = Request.Builder().url(urlFor("/api/instances")).build()
        val wrapper = execute<JsonObject>(req)
        val arr = wrapper["instances"] ?: throw IOException("missing 'instances' in response")
        return json.decodeFromJsonElement(arr)
    }

    suspend fun getInstance(id: String): InstanceSnapshot {
        val req = Request.Builder().url(urlFor("/api/instances/$id")).build()
        val obj = executeObject(req, "instance")
        return json.decodeFromJsonElement(obj)
    }

    suspend fun createInstance(
        name: String,
        cwd: String,
        lan: Boolean,
        port: Int?,
        app: InstanceAppProfile? = null,
        aa: Boolean? = null,
    ): InstanceSnapshot {
        val body = buildJsonObject {
            put("name", name)
            put("cwd", cwd)
            put("lan", lan)
            if (port != null) put("port", port)
            // AA per-instance 覆盖(对齐 web 端 InstanceDefinition.aa):
            //   null(缺省) = auto,跟随 root;true = 请求启用;false = 强制禁用。
            // 服务端 `parseBoolField` 只接受 `undefined | boolean` —— 发字面
            // `null` 会被 400,所以只在非 null 时才 put 这个 key。
            if (aa != null) put("aa", aa)
            // app 与 web 端 InstanceDefinition.app 字段对齐(0.8.0 新增,
            // 0.8.1 加 weixin):
            //   `task-factory` = 任务工厂实例(打开 /super-tasks)
            //   `weixin`       = 微信专用实例(独占微信通道 owner 锁)
            // 标准实例省略 key。服务端 `parseAppField` 拒绝 `null` / 未知字符串,
            // 所以这里只在 app != null 时才 put,避免把字面 `null` 发出去被 400。
            // 用显式 `when` 把每个 enum 值映射到字面量字符串 — Kotlin 编译器
            // 会在新增枚举值时给出 non-exhaustive 警告,避免新 profile 漏写。
            // 不引 `@SerialName` 反射 / 序列化器依赖,免得再加一种枚举就要多
            // 一份反射代码。
            if (app != null) {
                val appStr = when (app) {
                    InstanceAppProfile.TaskFactory -> "task-factory"
                    InstanceAppProfile.Weixin -> "weixin"
                }
                put("app", appStr)
            }
        }
        val req = Request.Builder()
            .url(urlFor("/api/instances"))
            .post(body.toString().toRequestBody(JSON))
            .build()
        return decodeInstance(executeObject(req, "instance"))
    }

    suspend fun startInstance(id: String): InstanceSnapshot =
        actionWithResponse("/api/instances/$id/start")

    suspend fun stopInstance(id: String): InstanceSnapshot =
        actionWithResponse("/api/instances/$id/stop")

    suspend fun restartInstance(id: String): InstanceSnapshot =
        actionWithResponse("/api/instances/$id/restart")

    private suspend fun actionWithResponse(path: String): InstanceSnapshot {
        val req = Request.Builder().url(urlFor(path)).post(EMPTY_BODY).build()
        return decodeInstance(executeObject(req, "instance", actionClient))
    }

    suspend fun deleteInstance(id: String) {
        execute<Unit>(Request.Builder().url(urlFor("/api/instances/$id")).delete().build())
    }

    suspend fun patchInstance(
        id: String,
        lan: PatchValue<Boolean>? = null,
        port: PatchValue<Int>? = null,
        aa: PatchValue<Boolean>? = null,
    ): InstanceSnapshot {
        val body = buildJsonObject {
            lan?.let {
                when (it) {
                    PatchValue.Null -> put("lan", JsonNull)
                    is PatchValue.Set -> put("lan", it.value)
                    PatchValue.Unset -> Unit
                }
            }
            port?.let {
                when (it) {
                    PatchValue.Null -> put("port", JsonNull)
                    is PatchValue.Set -> put("port", it.value)
                    PatchValue.Unset -> Unit
                }
            }
            // `aa` 与 lan/port 的三态**不同**:`parseBoolField` 只接受
            // `undefined | boolean`,发字面 `null` 会被 400。所以这里刻意
            // 不处理 `PatchValue.Null`(抛错而不是静默发 null),调用方表达
            // 「回到 auto」的唯一方式是 `PatchValue.Unset`(不发这个 key)——
            // 服务端读到的就是"没这个字段",保持 def.aa 原值不变。
            aa?.let {
                when (it) {
                    PatchValue.Null -> throw IllegalArgumentException(
                        "aa does not accept PatchValue.Null — the server rejects JSON null; " +
                            "use PatchValue.Unset to leave def.aa untouched"
                    )
                    is PatchValue.Set -> put("aa", it.value)
                    PatchValue.Unset -> Unit
                }
            }
        }
        val req = Request.Builder()
            .url(urlFor("/api/instances/$id"))
            .patch(body.toString().toRequestBody(JSON))
            .build()
        return decodeInstance(executeObject(req, "instance"))
    }

    private fun decodeInstance(obj: JsonObject): InstanceSnapshot =
        json.decodeFromJsonElement<InstanceSnapshot>(obj)

    // ===== fs picker =====

    suspend fun listDirectory(path: String): FsPickerList {
        val encoded = URLEncoder.encode(path, "UTF-8")
        val req = Request.Builder().url(urlFor("/api/fs/picker?path=$encoded")).build()
        return execute(req)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = "{}".toRequestBody(JSON)
    }
}