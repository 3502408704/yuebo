package com.example.local_music_player

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** 通过现有 FileProvider 分享字幕文件，不把字幕内容写入公共存储。 */
object VideoSubtitleShare {
    fun shareWebVtt(context: Context, title: String, cues: List<VideoSubtitleCue>): Result<Unit> =
        shareFile(
            context = context,
            title = title,
            extension = "vtt",
            mimeType = "text/vtt",
            content = VideoSubtitleExporter.toWebVtt(cues),
        )

    fun sharePlainText(context: Context, title: String, cues: List<VideoSubtitleCue>): Result<Unit> =
        shareFile(
            context = context,
            title = title,
            extension = "txt",
            mimeType = "text/plain",
            content = VideoSubtitleExporter.toPlainText(cues),
        )

    private fun shareFile(
        context: Context,
        title: String,
        extension: String,
        mimeType: String,
        content: String,
    ): Result<Unit> = runCatching {
        require(content.isNotBlank()) { "字幕内容为空" }
        val app = context.applicationContext
        val directory = File(app.cacheDir, "subtitle_exports").apply { mkdirs() }
        val safeTitle = title.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "subtitle" }
        val file = File(directory, "$safeTitle.$extension")
        file.writeText(content, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.update_provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        app.startActivity(
            Intent.createChooser(shareIntent, "分享字幕")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
