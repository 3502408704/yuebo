package com.example.local_music_player

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/** 内存与磁盘均保留最近诊断信息，native crash 后重启仍可导出崩溃前事件。 */
object AppErrorRecorder {
    private const val MAX = 50
    private const val MAX_FILE_BYTES = 256 * 1024
    private val queue = ConcurrentLinkedQueue<Entry>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
    private val fileLock = Any()
    @Volatile private var file: File? = null

    private data class Entry(val summary: String, val stack: String? = null)

    fun initialize(context: Context) {
        file = File(context.filesDir, "diagnostics/app-diagnostics.log").also { it.parentFile?.mkdirs() }
        event("应用", "启动 pid=${android.os.Process.myPid()}")
    }

    /** 记录一条异常。tag 用于分类（如 UI、投送、导入），throwable 为异常本体。 */
    fun record(tag: String, throwable: Throwable) {
        val top = throwable.stackTrace.firstOrNull()
        val where = top?.let { " @ ${it.className}.${it.methodName}(${it.fileName ?: "?"}:${it.lineNumber})" } ?: ""
        val summary = buildString {
            append(now())
            append(" [").append(tag).append("] ")
            append(throwable.javaClass.simpleName).append(": ").append(throwable.message ?: "未知错误")
            append(where)
        }
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        add(Entry(summary, sw.toString().trimEnd()))
    }

    /** 记录不抛异常的关键诊断事件，如外部解码器打开、切面或停止。 */
    fun event(tag: String, message: String) {
        add(Entry("${now()} [$tag] $message"))
    }

    /** 返回最近异常摘要（旧→新），无记录时返回空列表。 */
    fun snapshot(): List<String> = queue.map(Entry::summary)

    /** 返回带完整堆栈的异常记录（旧→新），供导出日志追加细节。 */
    fun snapshotWithStack(): List<Pair<String, String>> = queue.mapNotNull { entry ->
        entry.stack?.let { entry.summary to it }
    }

    fun persistentSnapshot(): String = synchronized(fileLock) {
        runCatching { file?.takeIf(File::isFile)?.readText().orEmpty() }.getOrDefault("")
    }

    fun clear() {
        queue.clear()
        synchronized(fileLock) { runCatching { file?.delete() } }
    }

    private fun add(entry: Entry) {
        queue.add(entry)
        while (queue.size > MAX) queue.poll()
        synchronized(fileLock) {
            runCatching {
                val target = file ?: return@runCatching
                target.parentFile?.mkdirs()
                target.appendText(entry.summary + "\n")
                entry.stack?.let { target.appendText(it + "\n") }
                if (target.length() > MAX_FILE_BYTES) {
                    target.writeText(target.readText().takeLast(MAX_FILE_BYTES))
                }
            }
        }
    }

    private fun now(): String = synchronized(timeFmt) { timeFmt.format(Date()) }
}
