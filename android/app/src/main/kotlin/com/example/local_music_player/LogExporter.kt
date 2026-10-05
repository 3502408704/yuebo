package com.example.local_music_player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 收集设备信息、应用日志与系统日志，供用户导出用于诊断问题。 */
object LogExporter {

    private fun runLogcat(args: List<String>): String =
        runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", *args.toTypedArray()))
            val out = process.inputStream.bufferedReader().readText()
            process.destroy()
            out.trim()
        }.getOrElse { e -> "（读取 logcat 失败：${e.javaClass.simpleName} ${e.message}）" }

    /** 生成日志文本文件，返回文件路径。文件保存在应用外部目录 logs/ 下。
     *  @param clearBefore 为 true 时先清空系统日志缓冲与本应用导出的旧日志文件，
     *  使导出的内容只包含清空后产生的日志，便于复现问题后收集精简日志。
     *  注意：崩溃缓冲与弃用 API 日志会在清空前读取，避免被一并清掉。 */
    fun collect(context: Context, clearBefore: Boolean = false): File {
        val app = context.applicationContext
        // 崩渍缓冲与弃用警告需在清空前读取，否则会被 logcat -c 抹掉
        val crashLog = runLogcat(listOf("-d", "-b", "crash", "-v", "threadtime")).let { if (it.isBlank()) "（无崩溃记录）" else it }
        val deprecationLog = extractDeprecationLog(app)
        if (clearBefore) clear(app)
        val sb = StringBuilder()
        sb.appendLine("==== 月播 日志导出 ====")
        sb.appendLine("导出时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        sb.appendLine("应用版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("包名: ${app.packageName}")
        sb.appendLine("设备: ${Build.BRAND} ${Build.MODEL}")
        sb.appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("架构: ${Build.SUPPORTED_ABIS.joinToString()}")
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.appendLine("总内存: ${mi.totalMem / 1024 / 1024} MB")
        }
        sb.appendLine()
        sb.appendLine("==== 最近异常摘要（仅内存记录，用于快速定位；不代表崩溃）====")
        val errors = AppErrorRecorder.snapshot()
        if (errors.isEmpty()) sb.appendLine("（无记录）") else errors.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("==== 最近异常完整堆栈（对应上方摘要）====")
        val stacks = AppErrorRecorder.snapshotWithStack()
        if (stacks.isEmpty()) sb.appendLine("（无记录）") else stacks.forEach { (summary, stack) ->
            sb.appendLine(summary)
            sb.appendLine(stack)
            sb.appendLine()
        }
        sb.appendLine()
        sb.appendLine("==== 持久诊断记录（跨进程保留，含外部解码器事件）====")
        sb.appendLine(AppErrorRecorder.persistentSnapshot().ifBlank { "（无记录）" })
        sb.appendLine()
        sb.appendLine("==== 系统崩溃堆栈（logcat crash 缓冲；本应用历次崩溃完整栈）====")
        sb.appendLine(crashLog)
        sb.appendLine()
        sb.appendLine("==== 系统弃用 API 日志（本应用相关）====")
        sb.appendLine(deprecationLog)
        sb.appendLine()
        sb.appendLine("==== 应用日志（本应用进程 logcat）====")
        sb.appendLine(runLogcat(listOf("-d", "-v", "threadtime", "--pid=${Process.myPid()}")).ifBlank { "（无日志）" })
        sb.appendLine()
        sb.appendLine("==== 系统日志（logcat 缓冲；普通应用仅可读取本应用日志）====")
        sb.appendLine(runLogcat(listOf("-d", "-v", "threadtime", "-t", "3000")).ifBlank { "（无日志）" })
        sb.appendLine()
        sb.appendLine("==== 结束 ====")

        val dir = File(app.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        val file = File(dir, "yuebo-logs-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
        file.writeText(sb.toString())
        return file
    }

    /** 从 logcat 中筛出本应用相关的弃用 API / StrictMode 警告。 */
    private fun extractDeprecationLog(context: Context): String {
        val pkg = context.packageName
        val raw = runLogcat(listOf("-d", "-v", "threadtime"))
        if (raw.isBlank()) return "（无日志）"
        val lines = raw.lineSequence().filter { line ->
            val lower = line.lowercase()
            (lower.contains("deprecated") || lower.contains("strictmode") || lower.contains("{p}")) &&
                (line.contains(pkg) || lower.contains("deprecated") || lower.contains("strictmode"))
        }.toList()
        return if (lines.isEmpty()) "（未发现本应用相关弃用 API 日志）" else lines.joinToString("\n")
    }

    /** 通过已有 FileProvider 生成可分享的文件 URI。 */
    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.update_provider", file)

    /** 清空系统 logcat 缓冲，并删除本应用此前导出的日志文件。返回是否成功。 */
    fun clear(context: Context): Boolean {
        val app = context.applicationContext
        AppErrorRecorder.clear()
        val logcatOk = runCatching {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
            process.waitFor()
            process.destroy()
            true
        }.getOrDefault(false)
        val filesOk = runCatching {
            val dir = File(app.getExternalFilesDir(null), "logs")
            if (dir.exists()) dir.listFiles()?.forEach { it.delete() }
            true
        }.getOrDefault(false)
        return logcatOk && filesOk
    }
}
