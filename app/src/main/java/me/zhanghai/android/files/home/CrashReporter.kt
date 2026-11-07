/*
 * Custom fork addition: records each uncaught exception to its own
 * file in the public Downloads folder (mf_crash_<date>_<time>.txt)
 * before the process dies, so a crash on the user's device leaves a
 * readable stack trace that can be shared on its own. Only the newest
 * MAX_FILES logs are kept.
 */

package me.zhanghai.android.files.home

import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    @Volatile
    private var installed = false

    private const val MAX_FILES = 30

    fun install() {
        if (installed) {
            return
        }
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val writer = StringWriter()
                throwable.printStackTrace(PrintWriter(writer))
                val now = Date()
                val fileStamp = SimpleDateFormat(
                    "yyyy-MM-dd_HH-mm-ss", Locale.US
                ).format(now)
                val stamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", Locale.US
                ).format(now)
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                var file = File(dir, "mf_crash_$fileStamp.txt")
                var suffix = 2
                while (file.exists()) {
                    file = File(dir, "mf_crash_${fileStamp}_$suffix.txt")
                    suffix++
                }
                file.writeText("===== $stamp =====\n$writer")
                pruneOldLogs(dir)
            } catch (e: Exception) {
                // The reporter must never break the crash path itself.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun pruneOldLogs(dir: File) {
        val logs = dir.listFiles { file ->
            file.name.startsWith("mf_crash_") &&
                file.name.endsWith(".txt") &&
                file.name != "mf_crash_log.txt"
        } ?: return
        if (logs.size <= MAX_FILES) {
            return
        }
        logs.sortedBy { it.lastModified() }
            .take(logs.size - MAX_FILES)
            .forEach { it.delete() }
    }
}
