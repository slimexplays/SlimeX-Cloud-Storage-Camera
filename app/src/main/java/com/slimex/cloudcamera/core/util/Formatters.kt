package com.slimex.cloudcamera.core.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Presentation helpers shared by every screen. */
object Formatters {

    fun bytes(bytes: Long): String {
        if (bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    fun clock(millis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(millis))

    fun relative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - epochMillis).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            days < 7 -> "${days}d ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(epochMillis))
        }
    }

    fun dateTime(epochMillis: Long): String =
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US).format(Date(epochMillis))

    fun hostOf(url: String): String =
        url.removePrefix("https://").removePrefix("http://").substringBefore("/").ifBlank { url }
}
