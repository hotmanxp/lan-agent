package io.github.hotmanxp.lanagent.aa.feature.files

import io.github.hotmanxp.lanagent.aa.api.ApiException
import io.github.hotmanxp.lanagent.aa.api.FilesApi
import io.github.hotmanxp.lanagent.aa.feature.auth.AuthSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilesController(
    /**
     * 公开（而不是 private）是为了让**同步**路径复用同一个实例 —— 只有这个实例
     * 接了 `onUnauthorized`，AA 登录过期时会走全局的清会话流程。`AaAsrProvider`
     * 走的就是这条路（`AsrUrlProvider.provide` 是同步的，跑在 io 线程上）。
     * 另起一个 `FilesApi()` 会安静地失去这个能力。
     */
    val filesApi: FilesApi,
    private val sessionStore: AuthSessionStore,
) {
    suspend fun listFiles(
        connectorId: String,
        root: String,
        path: String = ".",
    ): Result<FilesDirectory> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val directory = filesApi.listFiles(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    deviceId = connectorId,
                    root = root,
                    path = path,
                )
                FilesDirectory(
                    path = directory.path,
                    entries = directory.entries
                        .filter { it.type == "directory" || it.type == "file" }
                        .map {
                            FileEntry(
                                name = it.name,
                                path = it.path,
                                isDirectory = it.type == "directory",
                                size = it.size,
                            )
                        }
                        .sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }),
                )
            }.recoverCatching { error ->
                if (error is ApiException) throw error
                throw IllegalStateException(error.message ?: "Could not load files.", error)
            }
        }
    }

    suspend fun readTextFile(
        connectorId: String,
        root: String,
        path: String,
    ): Result<TextFile> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val file = filesApi.readTextFile(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    deviceId = connectorId,
                    root = root,
                    path = path,
                )
                TextFile(
                    path = file.path,
                    name = file.name,
                    size = file.size,
                    sha256 = file.sha256,
                    encoding = file.encoding,
                    content = file.content,
                    truncated = file.truncated,
                    binary = file.binary,
                )
            }.recoverCatching { error ->
                if (error is ApiException) throw error
                throw IllegalStateException(error.message ?: "Could not open file.", error)
            }
        }
    }

    private fun authSession(): ApiAuth {
        val serverUrl = sessionStore.readServerUrl()
        val accessToken = sessionStore.readAccessToken()
        if (serverUrl.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("Sign in again to browse files.")
        }
        return ApiAuth(serverUrl = serverUrl, accessToken = accessToken)
    }

    private data class ApiAuth(
        val serverUrl: String,
        val accessToken: String,
    )
}
