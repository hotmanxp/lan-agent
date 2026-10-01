// data/SessionToolsModels.kt — 会话「工作区」面板三栏的 wire 模型(0.24.11)
// 面板本身是 UI(见 ui/SessionToolsOverlay.kt),这里只放**从 zai 读回来的
// 数据形状**。三个数据源各自独立、彼此不共享生命周期,所以不合并成一个
// store,而是每栏一个纯数据类 + 一个 client 方法。
//
// ── 文件 ────────────────────────────────────────────────────────────────
// 数据源是 `GET /api/fs/list?dir=`(opencc-web `packages/zai/src/server/
// routes/fs.ts:345`)。
//
// **刻意不用 `/api/fs/picker`**:`fsPicker.ts:111-115` 的语义是「挑一个
// 目录」,对每个子项 `if (s.isDirectory()) isDir = true; else continue`
// ——**文件被整个丢弃**(行 120 那个 `'file'` 分支是死代码)。用它做文件
// 浏览会得到一棵没有可点文件的目录树。
//
// `dir` 是**相对 instance cwd** 的路径(经 `resolveSafePath` 锁在实例工作
// 目录内出不去),正斜杠分隔;`dir === ""` 即根。服务端已按「目录在前、组内
// 字母序」排好(fs.ts:392-396),客户端不要再排。
//
// 不返回 `parent` —— 上级得自己从相对路径截掉末段,见 [parentDirOrNull]。
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.Serializable

@Serializable
data class FsEntry(
    val name: String,
    /** 相对 instance cwd 的路径,正斜杠分隔 —— 可直接回传给下一次 `dir`。 */
    val path: String,
    /** `FsEntryType`;用 String 收,认不出的按"文件"处理而不是崩。 */
    val type: String = "file",
    /** 字节数;目录为 null。 */
    val size: Long? = null,
) {
    val isDirectory: Boolean get() = type == "dir"
}

@Serializable
data class FsList(
    val ok: Boolean = false,
    val error: String? = null,
    val entries: List<FsEntry> = emptyList(),
)

/**
 * 相对路径的上级;**已在根则返回 null** —— UI 据此禁用上级按钮。
 *
 * 与 [parentDir](TurnArtifacts.kt) 的区别只有一条:那条把「顶层」和「根」
 * 都返回 `""`,展示用够了;这里要区分二者 —— 顶层目录的上级是根(能上去),
 * 根没有上级(按钮该禁用)。
 *
 * 纯字符串处理,不做规范化:服务端的 `path` 本来就是规范化过的相对路径
 * (fs.ts 内部 `resolve` + `split(sep).join('/')`),这里只需要截末段。
 * 末尾的 `/` 容忍一下,免得手拼路径时多一个空段。
 */
internal fun parentDirOrNull(dir: String): String? {
    val trimmed = dir.trimEnd('/')
    if (trimmed.isEmpty()) return null
    val idx = trimmed.lastIndexOf('/')
    return if (idx < 0) "" else trimmed.substring(0, idx)
}

/**
 * `GET /api/system` 里我们只用的两个字段(`routes/system.ts:83`)。
 *
 * **存在的唯一理由是路径不对称的补丁**:`/api/fs/list` 返回**相对**路径(锁在
 * instance cwd 内),而 `FileViewerOverlay` 那条预览链路
 * (`/api/fs/preview`、`/api/fs/raw`)走 `pathResolve(raw)` 且**明确不限 cwd**
 * (`fs.ts:1085` 的 `void cwd // 不限 cwd`),要的是**绝对**路径。两边接不上,
 * 就得先问出 instance cwd 拼一拼。
 */
@Serializable
data class SystemCwd(
    val cwd: String = "",
    /** cwd 的末段,给路径条当短标签用。 */
    val cwdName: String = "",
)

/**
 * 把 `/api/fs/list` 的相对路径拼成绝对路径。
 *
 * 刻意**不**在客户端做 normalize / 解析 `..` —— 服务端返回的 `path` 已经是
 * 规范化过的正斜杠相对路径(`fs.ts:390` 的 `split(sep).join('/')`),直接拼就
 * 是对的;在这里自作聪明地处理 `..` 反而会引入越界的可能。
 */
internal fun absUnderCwd(cwd: String, relPath: String): String {
    if (relPath.isEmpty()) return cwd
    val base = cwd.trimEnd('/', '\\')
    val rel = relPath.trimStart('/', '\\')
    return "$base/$rel"
}

// ── git ─────────────────────────────────────────────────────────────────
// 单端点 action 分发:`POST /api/git`(opencc-web `routes/git.ts:163`)。
//
// ⚠️ 两个必须钉死的 wire 事实,照老的类型写会**静默解不出东西**:
//
//  1. 字段是 **`entries`** 不是 `files`。`shared/git.ts:75` 的
//     `GitStatusEntry` 才是权威类型;同文件里那个老的 `GitStatusFile`
//     (带 `files` / 单字符 `status`)已被路由层取代,别照它写。
//  2. 每项的状态是 **`xy` 两字符**,直接照抄 `git status --porcelain=v1`
//     的两列 —— `" M"`(工作区改)/ `"M "`(已暂存)/ `"A "` / `"??"`。
//     两列都有意义:左列=暂存区,右列=工作区。压成单字符会丢掉一半信息。
@Serializable
data class GitStatusEntry(
    val path: String,
    /** 两字符 porcelain 码,如 `" M"` / `"A "` / `"??"`。 */
    val xy: String = "",
    /** 便捷字段:暂存区那列非空格。服务端已算好,别自己从 xy 推。 */
    val staged: Boolean = false,
) {
    /**
     * 工作区那列 —— `xy` 的第二个字符,**原样返回**(空格就是空格)。
     * 要拿徽标字母时用 `xy.trim()` 或自己判。
     */
    val worktree: String get() = if (xy.length >= 2) xy[1].toString() else ""

    /**
     * 未跟踪 —— `xy == "??"`。
     *
     * **这个判定必须在客户端做**:服务端 `git.ts:209` 的 diff 分支把
     * `isUntracked` 硬编码成 `false`,而 `svcDiff`(gitService.ts:413)跑的
     * 是 `git diff`,按定义不包含未跟踪文件。所以点一条 `??` 会拿到
     * **空 diff 且没有任何报错**。别把它当服务端 bug 去修,用这个标记
     * 在 UI 上如实说明(见 ui/SessionToolsOverlay.kt 的 git 栏)。
     */
    val isUntracked: Boolean get() = xy == "??"
}

@Serializable
data class GitStatus(
    val ok: Boolean = false,
    val error: String? = null,
    val branch: String? = null,
    val entries: List<GitStatusEntry> = emptyList(),
    /** 未跟踪文件刷屏时服务端截断到 GIT_STATUS_LIMIT(2000),如实透出。 */
    val truncated: Boolean = false,
    val root: String? = null,
    val repositories: List<String> = emptyList(),
) {
    /** 干净的仓库 —— 与「不是仓库」是两件事,空态文案要分开。 */
    val isClean: Boolean get() = ok && entries.isEmpty()
}

@Serializable
data class GitDiff(
    val ok: Boolean = false,
    val error: String? = null,
    val diff: String = "",
    /**
     * 服务端恒为 false(见 [GitStatusEntry.isUntracked] 的说明),保留字段
     * 只是为了将来服务端修了不用改客户端。
     */
    val isUntracked: Boolean = false,
)

// ── Bash REPL ────────────────────────────────────────────────────────────
// `packages/zai/src/server/routes/bashRepl.ts`。**不是 PTY**:每次 exec 起
// 一个子进程(`ReplSession.exec`),没有 TTY 回显、没有 resize、没有持续
// shell 会话。所以 UI 是「输出列表 + 单行输入」,不是 xterm。
@Serializable
data class ReplEvent(
    /** `stdout` | `stderr` | `exit` | `error`。 */
    val kind: String = "",
    val execId: String = "",
    /** 仅 stdout / stderr。 */
    val chunk: String? = null,
    /** 仅 exit。 */
    val code: Int? = null,
    /** 仅 exit。 */
    val signal: String? = null,
    /** 仅 error。 */
    val message: String? = null,
    val ts: Long = 0L,
)

/** `POST /api/bash/repl/{sid}/exec` 的响应(`wait: true` 形态)。 */
@Serializable
data class ReplExecResult(
    val ok: Boolean = false,
    val execId: String? = null,
    val code: Int? = null,
    val signal: String? = null,
    val durationMs: Long? = null,
)
