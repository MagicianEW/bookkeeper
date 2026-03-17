package com.simplebookkeeper.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 应用日志管理器
 * - 将 INFO / WARN / ERROR 写入 files/logs/ 目录下的日期文件
 * - 最多保留 7 天日志，自动清理过期文件
 * - 提供 exportAll() 将所有日志合并为一个 File 供分享
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val LOG_DIR = "logs"
    private const val MAX_KEEP_DAYS = 7
    private const val MAX_FILE_SIZE_MB = 5

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private lateinit var logDir: File
    private val queue = LinkedBlockingQueue<String>(10_000)
    private val started = AtomicBoolean(false)

    /** 必须在 Application.onCreate() 中调用一次 */
    fun init(context: Context) {
        logDir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        if (started.compareAndSet(false, true)) {
            startWriterThread()
            cleanOldLogs()
        }
        i("AppLogger", "=== 日志系统已启动，日志目录: ${logDir.absolutePath} ===")
    }

    // ─── 公开接口 ────────────────────────────────────────────────

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        enqueue("I", tag, msg, null)
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        Log.w(tag, msg, throwable)
        enqueue("W", tag, msg, throwable)
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        enqueue("E", tag, msg, throwable)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        enqueue("D", tag, msg, null)
    }

    /**
     * 将所有日志文件合并为一个临时文件，用于分享导出。
     * @return 合并后的临时文件（调用方负责使用后删除）
     */
    fun exportAll(context: Context): File {
        val exportFile = File(context.cacheDir, "bookkeeper_logs_export.txt")
        exportFile.bufferedWriter().use { out ->
            out.write("=== SimpleBookkeeper 日志导出 ===\n")
            out.write("导出时间: ${timeFormat.format(Date())}\n")
            out.write("=================================\n\n")

            logDir.listFiles()
                ?.filter { it.extension == "log" }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    out.write("\n──── ${file.name} ────\n")
                    out.write(file.readText())
                }
        }
        return exportFile
    }

    // ─── 内部实现 ────────────────────────────────────────────────

    private fun enqueue(level: String, tag: String, msg: String, t: Throwable?) {
        if (!started.get()) return
        val sb = StringBuilder()
        sb.append(timeFormat.format(Date()))
        sb.append(" [").append(level).append("] ")
        sb.append(tag).append(": ").append(msg)
        if (t != null) {
            sb.append("\n")
            val sw = java.io.StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append(sw.toString())
        }
        sb.append("\n")
        queue.offer(sb.toString())
    }

    private fun startWriterThread() {
        thread(isDaemon = true, name = "AppLoggerWriter") {
            while (true) {
                try {
                    val line = queue.take()          // 阻塞直到有数据
                    writeToFile(line)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "日志写入线程异常", e)
                }
            }
        }
    }

    private fun writeToFile(line: String) {
        val fileName = "${dateFormat.format(Date())}.log"
        val file = File(logDir, fileName)
        // 超过大小限制时截断旧内容（保留后半段）
        if (file.exists() && file.length() > MAX_FILE_SIZE_MB * 1024 * 1024) {
            val content = file.readText()
            val half = content.substring(content.length / 2)
            file.writeText("[已截断旧日志]\n$half")
        }
        FileWriter(file, true).use { it.write(line) }
    }

    private fun cleanOldLogs() {
        thread(isDaemon = true, name = "AppLoggerCleaner") {
            try {
                val cutoff = System.currentTimeMillis() - MAX_KEEP_DAYS * 24 * 3600 * 1000L
                logDir.listFiles()
                    ?.filter { it.isFile && it.lastModified() < cutoff }
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.w(TAG, "清理旧日志失败", e)
            }
        }
    }
}
