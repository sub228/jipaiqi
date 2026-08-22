package com.jipaiqi.doudizhu

import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-memory ring buffer that keeps the most recent [CAP] log lines emitted
 * from anywhere in the app (Application.onCreate, MainActivity.onCreate,
 * the logcat-capture thread, the UncaughtExceptionHandler…).
 *
 * The reason this exists: on HarmonyOS 7.0 / Android 11+ the user often
 * cannot find the `Android/data/<pkg>/cache/` or even `/sdcard/Download/`
 * directory where logs are also written, so a file-based log alone is
 * useless for triage.  Keeping a copy in memory lets the in-app "查看日志"
 * dialog surface the same content with a one-tap copy button — no file
 * manager needed.
 */
object LogStore {
    private const val CAP = 2000
    private val deque = ConcurrentLinkedDeque<String>()

    @Volatile var bootLogPath: String? = null
        private set
    @Volatile var logcatCapturePath: String? = null
        private set
    @Volatile var crashLogPath: String? = null
        private set

    fun recordBootPath(file: File) { bootLogPath = file.absolutePath }
    fun recordLogcatPath(file: File) { logcatCapturePath = file.absolutePath }
    fun recordCrashPath(file: File) { crashLogPath = file.absolutePath }

    fun append(msg: String) {
        val line = "[${Date()}] $msg"
        deque.addLast(line)
        while (deque.size > CAP) deque.pollFirst()
    }

    fun snapshot(): String =
        deque.joinToString("\n").ifEmpty { "(empty log buffer)" }

    fun snapshotWithFiles(): String {
        val sb = StringBuilder()
        sb.append("========== 内存日志缓冲 ==========\n")
        sb.append(snapshot())
        sb.append("\n\n")
        bootLogPath?.let { path ->
            sb.append("========== boot_log.txt ($path) ==========\n")
            sb.append(runCatching { File(path).readText() }.getOrElse { "(read failed: ${it.message})" })
            sb.append("\n\n")
        }
        logcatCapturePath?.let { path ->
            sb.append("========== logcat_capture.txt ($path) ==========\n")
            sb.append(runCatching { File(path).readText() }.getOrElse { "(read failed: ${it.message})" })
            sb.append("\n\n")
        }
        crashLogPath?.let { path ->
            sb.append("========== 最新 crash_log ($path) ==========\n")
            sb.append(runCatching { File(path).readText() }.getOrElse { "(read failed: ${it.message})" })
            sb.append("\n")
        }
        return sb.toString()
    }

    fun clear() { deque.clear() }
}
