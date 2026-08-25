// ui/InstanceFormat.kt — 运行时长 / 最后心跳 / 时间戳格式化 helper。
//
// 字符串对齐 web 端 Instances.tsx 的 formatDuration / relativeAgo,
// 字段不同时格式不同(空 → "-";未来 → "刚刚")。
package io.github.hotmanxp.lanagent.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private val LOCAL_FMT = SimpleDateFormat("yyyy/M/d HH:mm:ss", Locale.getDefault())

/** 把 ISO-8601 时间戳(如 2026-08-24T05:39:02.629Z)按本地时区格式化成 yyyy/M/d HH:mm:ss;空/解析失败 → "-"。 */
fun formatTimestamp(iso: String?, now: Long = System.currentTimeMillis()): String {
    if (iso.isNullOrBlank()) return "-"
    return runCatching {
        val ms = parseIsoMs(iso)
        LOCAL_FMT.format(Date(ms))
    }.getOrDefault("-")
}

/** 运行时长:startedAt → now 的时长。可读格式 "1天7小时" / "21小时29分" / "1分23秒" / "23秒"。空 → "-" */
fun formatRuntimeMs(ms: Long): String {
    if (ms < 0) return "-"
    val totalSec = ms / 1000
    val days = totalSec / 86400
    val hours = (totalSec % 86400) / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return when {
        days > 0 -> "${days}天${hours}小时"
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

/** 相对时间:"5 秒前" / "2 分钟前" / "3 小时前";未来 → "刚刚";空 → "-" */
fun formatRelativeAgo(iso: String?, now: Long = System.currentTimeMillis()): String {
    if (iso.isNullOrBlank()) return "-"
    val ms = runCatching { parseIsoMs(iso) }.getOrNull() ?: return "-"
    val delta = now - ms
    if (delta < 0) return "刚刚"
    val sec = delta / 1000
    if (sec < 60) return "${sec} 秒前"
    val min = sec / 60
    if (min < 60) return "${min} 分钟前"
    val hr = min / 60
    return "${hr} 小时前"
}

private fun parseIsoMs(iso: String): Long {
    // 支持 "...Z" 与带时区偏移两种形式;Java 自带 DateTimeFormatter 解析 ISO_INSTANT。
    val normalized = if (iso.endsWith("Z")) iso else "${iso}Z"
    return java.time.Instant.parse(normalized).toEpochMilli()
}

/** 同 parseIsoMs 但失败返回 null,给 UI 用(运行时长 / 心跳相对时间需要兜底)。 */
internal fun parseIsoMsOrNull(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { parseIsoMs(iso) }.getOrNull()
}