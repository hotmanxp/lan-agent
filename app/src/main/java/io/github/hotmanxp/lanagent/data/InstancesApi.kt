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

    private suspend inline fun <reified T> execute(req: Request): T = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
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

    private suspend fun executeObject(req: Request, key: String): JsonObject {
        val wrapper = execute<JsonObject>(req)
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
        kernel: InstanceKernel?,
    ): InstanceSnapshot {
        val body = buildJsonObject {
            put("name", name)
            put("cwd", cwd)
            put("lan", lan)
            if (port != null) put("port", port)
            if (kernel != null) put("kernel", kernel.name)
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
        return decodeInstance(executeObject(req, "instance"))
    }

    suspend fun deleteInstance(id: String) {
        execute<Unit>(Request.Builder().url(urlFor("/api/instances/$id")).delete().build())
    }

    suspend fun patchInstance(
        id: String,
        lan: PatchValue<Boolean>? = null,
        port: PatchValue<Int>? = null,
        kernel: PatchValue<InstanceKernel>? = null,
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
            kernel?.let {
                when (it) {
                    PatchValue.Null -> put("kernel", JsonNull)
                    is PatchValue.Set -> put("kernel", it.value.name)
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