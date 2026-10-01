// app/src/test/.../data/SessionToolsModelsTest.kt
//
// 钉「工作区」面板三栏的 wire 坑。这些坑的共同点:**照直觉写会静默失败**
// —— 不抛异常、列表变空、diff 变空,排查起来极难,所以在这里用真实的服务端
// 响应片段钉死。
//
// 对应的服务端真源:
//   GET  /api/fs/list              opencc-web routes/fs.ts:345
//   POST /api/git {action:status}  opencc-web routes/git.ts:163,180
//   POST /api/git {action:diff}    opencc-web routes/git.ts:196
//   GET  /api/bash/repl/:id/events opencc-web routes/bashRepl.ts:73
package io.github.hotmanxp.lanagent.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionToolsModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    // ===== /api/fs/list =====

    @Test
    fun `fsList 解析目录与文件 size 为 null`() {
        // 真实响应形状:目录在前、组内字母序由服务端排好(fs.ts:392-396)。
        val body = """
            {"ok":true,"entries":[
              {"name":"packages","path":"packages","type":"dir","size":null},
              {"name":"AGENTS.md","path":"AGENTS.md","type":"file","size":24310}
            ]}
        """.trimIndent()
        val list = json.decodeFromString<FsList>(body)

        assertTrue(list.ok)
        assertEquals(2, list.entries.size)
        assertTrue(list.entries[0].isDirectory)
        assertNull(list.entries[0].size)
        assertFalse(list.entries[1].isDirectory)
        assertEquals(24310L, list.entries[1].size)
    }

    @Test
    fun `fsList 失败态只有 ok 和 error 没有 entries`() {
        // 403 越界 / 404 目录不存在 / 500 读盘失败 都是这个形状
        // (fs.ts:350/360/364)。entries 缺省时必须给空列表而不是崩。
        val body = """{"ok":false,"error":"禁止访问：路径越界 (../etc)"}"""
        val list = json.decodeFromString<FsList>(body)

        assertFalse(list.ok)
        assertEquals("禁止访问：路径越界 (../etc)", list.error)
        assertTrue(list.entries.isEmpty())
    }

    @Test
    fun `fsList 认不出的 type 当文件处理而不是崩`() {
        // 服务端只有 'dir'/'file' 两种,但类型收 String 是有意为之 ——
        // 将来加了新类型(符号链接/设备文件)不该让整页反序列化失败。
        val body = """{"ok":true,"entries":[{"name":"x","path":"x","type":"weird"}]}"""
        val entry = json.decodeFromString<FsList>(body).entries.single()

        assertFalse(entry.isDirectory)
    }

    // ===== parentDirOrNull(客户端自己算上级,服务端不返回 parent)=====

    @Test
    fun `parentDirOrNull 逐层上跳并在根返回 null`() {
        // 必须区分「顶层」与「根」:顶层还能往根上跳(返回 "")，根没有上级
        // (返回 null,UI 据此禁用按钮)。展示用的 parentDir 把两者都返回 ""。
        assertNull(parentDirOrNull(""))
        assertNull(parentDirOrNull("/"))
        assertEquals("", parentDirOrNull("packages"))
        assertEquals("packages", parentDirOrNull("packages/zai"))
        assertEquals("packages/zai", parentDirOrNull("packages/zai/src"))
    }

    @Test
    fun `parentDirOrNull 容忍末尾斜杠`() {
        // 手拼路径时多一个 '/' 不该让上级算成空串(那样会"跳回根"而不是上一级)
        assertEquals("packages", parentDirOrNull("packages/zai/"))
    }

    // ===== POST /api/git {action:status} =====

    @Test
    fun `gitStatus 字段是 entries 不是 files`() {
        // ⚠️ 核心坑:老类型 GitStatusFile 用的是 `files`。真源是
        // shared/git.ts:75 的 GitStatusEntry + git.ts:184 的 `entries: result.entries`。
        // 写成 files 的话 —— 不报错,列表永远空。
        val body = """
            {"ok":true,"branch":"main","entries":[
              {"path":"src/app.ts","xy":" M","staged":false},
              {"path":"new.md","xy":"??","staged":false}
            ],"truncated":false,"root":"/repo","repositories":["/repo"]}
        """.trimIndent()
        val status = json.decodeFromString<GitStatus>(body)

        assertTrue(status.ok)
        assertEquals("main", status.branch)
        assertEquals(2, status.entries.size)
        assertEquals("src/app.ts", status.entries[0].path)
    }

    @Test
    fun `gitStatusEntry 状态是 xy 两字符且能拆出两列`() {
        // xy 直接照抄 `git status --porcelain=v1`:左列=暂存区,右列=工作区。
        // **两列都是原始字符,空格就是空格** —— "M " 的工作区列是空格而
        // 不是空串。UI 要拿徽标字母时自己 trim,别指望这里替你抹掉。
        val body = """
            {"ok":true,"entries":[
              {"path":"a.txt","xy":"M ","staged":true},
              {"path":"b.txt","xy":" M","staged":false},
              {"path":"c.txt","xy":"A ","staged":true}
            ]}
        """.trimIndent()
        val entries = json.decodeFromString<GitStatus>(body).entries

        // "M " —— 只有暂存区那列有值
        assertTrue(entries[0].staged)
        assertEquals(" ", entries[0].worktree)
        assertEquals("M", entries[0].xy.trim())
        // " M" —— 只有工作区那列有值
        assertFalse(entries[1].staged)
        assertEquals("M", entries[1].worktree)
        // "A " —— A 在**暂存区**那列,工作区列是空格
        assertEquals(" ", entries[2].worktree)
        assertEquals("A", entries[2].xy.trim())
        // 两列都空不可能出现(porcelain 至少有一列有值),但不该崩
        assertEquals("", json.decodeFromString<GitStatus>(
            """{"ok":true,"entries":[{"path":"x","xy":"","staged":false}]}"""
        ).entries.single().worktree)
    }

    @Test
    fun `gitStatusEntry 未跟踪由 xy 等于双问号判定`() {
        // 这是客户端必须自己算的东西:服务端 git.ts:209 把 diff 响应的
        // isUntracked 硬编码成 false,所以未跟踪只能从 status 行的 xy 推。
        val body = """
            {"ok":true,"entries":[
              {"path":"untracked.txt","xy":"??","staged":false},
              {"path":"tracked.txt","xy":" M","staged":false}
            ]}
        """.trimIndent()
        val entries = json.decodeFromString<GitStatus>(body).entries

        assertTrue(entries[0].isUntracked)
        assertFalse(entries[1].isUntracked)
    }

    @Test
    fun `gitStatus 缺 xy 时退化成空串而不是抛异常`() {
        val body = """{"ok":true,"entries":[{"path":"weird","staged":false}]}"""
        val entry = json.decodeFromString<GitStatus>(body).entries.single()

        assertEquals("", entry.xy)
        assertEquals("", entry.worktree)
        assertFalse(entry.isUntracked)
    }

    @Test
    fun `gitStatus 非仓库是 ok false 不是空列表`() {
        // gitService.ts:383 明确 throw 而不是返回 {isRepo:false},让路由层
        // 包成 {ok:false,error}。所以「不是仓库」与「仓库干净」是两种空态,
        // 判别依据是 ok —— 只看 entries.isEmpty() 会把两者混成一句话。
        val body = """{"ok":false,"error":"not a git repository"}"""
        val status = json.decodeFromString<GitStatus>(body)

        assertFalse(status.ok)
        assertEquals("not a git repository", status.error)
        assertFalse(status.isClean)
    }

    @Test
    fun `gitStatus 干净仓库 isClean 为真`() {
        val body = """{"ok":true,"branch":"main","entries":[],"truncated":false}"""
        val status = json.decodeFromString<GitStatus>(body)

        assertTrue(status.isClean)
    }

    @Test
    fun `gitStatus truncated 透传`() {
        // GIT_STATUS_LIMIT = 2000(gitService.ts:372),超了要如实提示而不是
        // 让用户以为"就这些改动"。
        val body = """{"ok":true,"branch":"main","entries":[],"truncated":true}"""
        assertTrue(json.decodeFromString<GitStatus>(body).truncated)
    }

    // ===== POST /api/git {action:diff} =====

    @Test
    fun `gitDiff 未跟踪文件返回空 diff 且 ok 为真`() {
        // ⚠️ 核心坑:git.ts:209 硬编码 isUntracked:false,svcDiff 跑的是
        // `git diff` —— 按定义不含未跟踪文件。**空 diff 不代表出错**,
        // UI 必须靠 status 行的 xy 判出未跟踪并说明,否则用户以为点错了。
        val body = """{"ok":true,"diff":"","isUntracked":false}"""
        val diff = json.decodeFromString<GitDiff>(body)

        assertTrue(diff.ok)
        assertEquals("", diff.diff)
        assertFalse(diff.isUntracked)
    }

    @Test
    fun `gitDiff 缺 diff 字段退化成空串`() {
        val body = """{"ok":true}"""
        assertEquals("", json.decodeFromString<GitDiff>(body).diff)
    }

    // ===== /api/bash/repl/:id/events =====

    @Test
    fun `replEvent 四种 kind 各自解析`() {
        val stdout = json.decodeFromString<ReplEvent>(
            """{"kind":"stdout","execId":"e1","chunk":"hello\n","ts":1}"""
        )
        assertEquals("stdout", stdout.kind)
        assertEquals("hello\n", stdout.chunk)

        val stderr = json.decodeFromString<ReplEvent>(
            """{"kind":"stderr","execId":"e1","chunk":"oops","ts":2}"""
        )
        assertEquals("oops", stderr.chunk)

        // code 可能是 null(被信号杀死时),别让它崩
        val exit = json.decodeFromString<ReplEvent>(
            """{"kind":"exit","execId":"e1","code":0,"signal":null,"ts":3}"""
        )
        assertEquals(0, exit.code)
        assertNull(exit.signal)
        assertNull(exit.chunk)

        val error = json.decodeFromString<ReplEvent>(
            """{"kind":"error","execId":"e1","message":"spawn failed","ts":4}"""
        )
        assertEquals("spawn failed", error.message)
    }

    @Test
    fun `replExecResult 解析 wait 模式的退出信息`() {
        val body = """
            {"ok":true,"execId":"e1","startedAt":100,"finishedAt":350,
             "code":0,"signal":null,"durationMs":250}
        """.trimIndent()
        val result = json.decodeFromString<ReplExecResult>(body)

        assertTrue(result.ok)
        assertEquals("e1", result.execId)
        assertEquals(0, result.code)
        assertEquals(250L, result.durationMs)
    }
}
