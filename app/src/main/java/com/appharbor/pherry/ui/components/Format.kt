package com.appharbor.pherry.ui.components

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Shared number, size and time formatting so every screen prints the same way. */
object Fmt {
    fun count(n: Int): String = NumberFormat.getIntegerInstance().format(n)
    fun count(n: Long): String = NumberFormat.getIntegerInstance().format(n)

    private const val KB = 1024.0
    private const val MB = KB * 1024
    private const val GB = MB * 1024

    // A no-break space keeps a number on the line with its unit; a word joiner keeps "MB/s" in one piece.
    private const val NBSP = "\u00A0"
    private const val PER_SECOND = "/\u2060s"

    // Each unit takes over where the smaller one would round up to "1024" ("1024 KB" reads as "1.0 MB").
    fun bytes(bytes: Long): String = when {
        bytes >= GB - MB * 0.05 -> "%.1f${NBSP}GB".format(bytes / GB)
        bytes >= MB - KB * 0.5 -> "%.1f${NBSP}MB".format(bytes / MB)
        bytes >= 1024 -> "%.0f${NBSP}KB".format(bytes / KB)
        bytes > 0 -> "$bytes${NBSP}B"
        else -> "0${NBSP}B"
    }

    fun speed(bytesPerSec: Long): String = when {
        bytesPerSec >= MB - KB * 0.5 -> "%.1f${NBSP}MB$PER_SECOND".format(bytesPerSec / MB)
        bytesPerSec >= 1024 -> "%.0f${NBSP}KB$PER_SECOND".format(bytesPerSec / KB)
        else -> "$bytesPerSec${NBSP}B$PER_SECOND"
    }

    /** "2 h 5 min left", "4 min left", "40 s left"; empty when unknown. */
    fun remaining(seconds: Long): String = when {
        seconds <= 0 -> ""
        seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min left"
        seconds >= 60 -> "${seconds / 60} min left"
        else -> "$seconds s left"
    }

    /** "photo" / "photos" with a localized count. */
    fun plural(n: Int, one: String, many: String = one + "s"): String = "${count(n)} ${if (n == 1) one else many}"

    /** Stamp date: "Oct 1 · 23:14", with the year when it isn't this year. */
    fun stamp(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val thisYear = Calendar.getInstance().get(Calendar.YEAR) == cal.get(Calendar.YEAR)
        val pattern = if (thisYear) "MMM d · HH:mm" else "MMM d yyyy · HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
    }

    /** "Just now", "12 min ago", "3 h ago", "Yesterday", or a date. */
    fun ago(timestamp: Long): String {
        if (timestamp <= 0) return ""
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000L -> "Just now"
            diff < 3_600_000L -> "${diff / 60_000L} min ago"
            diff < 86_400_000L -> "${diff / 3_600_000L} h ago"
            diff < 172_800_000L -> "Yesterday"
            else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** Day bucket label for grouped lists: "Today", "Yesterday", "Mon, Sep 29". */
    fun day(timestamp: Long): String {
        if (timestamp <= 0) return "Earlier"
        val now = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = timestamp }
        val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(c, now) -> "Today"
            same(c, y) -> "Yesterday"
            c.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** "14:02". */
    fun clock(timestamp: Long): String =
        if (timestamp <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    fun isVideoName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "mkv", "webm", "3gp", "m4v", "avi")
}
