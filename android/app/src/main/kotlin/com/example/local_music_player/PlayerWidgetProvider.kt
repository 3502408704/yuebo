package com.example.local_music_player

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

internal data class PlayerWidgetSnapshot(
    val title: String,
    val artist: String,
    val lyric: String,
    val playing: Boolean,
)

internal fun shouldRefreshWidget(previous: PlayerWidgetSnapshot, current: PlayerWidgetSnapshot): Boolean =
    previous != current

internal fun widgetAccessibilitySummary(title: String, artist: String, lyric: String): String {
    val song = title.ifBlank { "未知歌曲" }
    val singer = artist.ifBlank { "未知歌手" }
    val currentLyric = lyric.ifBlank { "暂无歌词" }
    return "歌曲：$song，歌手：$singer，歌词：$currentLyric"
}

/**
 * 播放控制桌面小部件。
 * - 标题区点击打开应用；三个按钮控制播放。
 * - 播放/暂停按钮的无障碍描述随状态切换为"播放"或"暂停"。
 * - [NativeMusicViewModel] 统一广播歌曲、歌手、歌词和播放状态快照。
 */
class PlayerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val snapshot = loadSnapshot(context)
        val views = render(
            context = context,
            title = snapshot.title,
            artist = snapshot.artist,
            lyric = snapshot.lyric,
            playing = snapshot.playing,
        )
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_PAUSE -> forward(context, MediaPlaybackService.ACTION_PLAY_PAUSE, intent.getBooleanExtra(EXTRA_PLAYING, false))
            ACTION_PREVIOUS -> forward(context, MediaPlaybackService.ACTION_PREVIOUS, false)
            ACTION_NEXT -> forward(context, MediaPlaybackService.ACTION_NEXT, false)
            ACTION_OPEN_APP -> context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: context.getString(R.string.widget_default_title)
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: context.getString(R.string.widget_default_artist)
                val lyric = intent.getStringExtra(EXTRA_LYRIC) ?: context.getString(R.string.widget_default_lyric)
                val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
                refreshAll(context, title, artist, lyric, playing)
            }
            else -> super.onReceive(context, intent)
        }
    }

    private fun forward(context: Context, action: String, playing: Boolean) {
        val intent = Intent(context, MediaPlaybackService::class.java)
            .setAction(action)
            .putExtra(MediaPlaybackService.EXTRA_PLAYING, playing)
        runCatching { context.startService(intent) }
    }

    /** 统一渲染信息摘要、可见文本和三个播放控制。 */
    private fun render(context: Context, title: String, artist: String, lyric: String, playing: Boolean): RemoteViews {
        val mergedTitle = buildMergedTitle(context, title, artist)
        val views = RemoteViews(context.packageName, R.layout.widget_player)
        views.setTextViewText(R.id.widget_title, mergedTitle)
        views.setTextViewText(R.id.widget_lyric, lyric.ifBlank { context.getString(R.string.widget_default_lyric) })
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        views.setContentDescription(R.id.widget_title, mergedTitle)
        views.setContentDescription(
            R.id.widget_lyric,
            "歌词：${lyric.ifBlank { context.getString(R.string.widget_default_lyric) }}",
        )
        views.setContentDescription(
            R.id.widget_play_pause,
            context.getString(if (playing) R.string.widget_pause else R.string.widget_play),
        )
        views.setOnClickPendingIntent(R.id.widget_title, pending(context, ACTION_OPEN_APP))
        views.setOnClickPendingIntent(R.id.widget_previous, pending(context, ACTION_PREVIOUS))
        views.setOnClickPendingIntent(R.id.widget_play_pause, pending(context, ACTION_PLAY_PAUSE, playing))
        views.setOnClickPendingIntent(R.id.widget_next, pending(context, ACTION_NEXT))
        return views
    }

    private fun buildMergedTitle(context: Context, title: String, artist: String): String {
        val song = title.ifBlank { context.getString(R.string.widget_default_title) }
        val singer = artist.ifBlank { "" }
        return if (singer.isBlank()) song else "$song - $singer"
    }

    /** 用最新歌曲、歌手、歌词和播放状态刷新所有 widget 实例。 */
    private fun refreshAll(context: Context, title: String, artist: String, lyric: String, playing: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PlayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = render(context, title, artist, lyric, playing)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    private fun pending(context: Context, action: String, playing: Boolean = false): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val intent = Intent(context, PlayerWidgetProvider::class.java)
            .setAction(action)
            .putExtra(EXTRA_PLAYING, playing)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "widget_play_pause"
        const val ACTION_PREVIOUS = "widget_previous"
        const val ACTION_NEXT = "widget_next"
        const val ACTION_UPDATE = "widget_update"
        const val ACTION_OPEN_APP = "widget_open_app"
        private const val EXTRA_TITLE = "widget_title"
        private const val EXTRA_ARTIST = "widget_artist"
        private const val EXTRA_LYRIC = "widget_lyric"
        private const val EXTRA_PLAYING = "widget_playing"
        private const val PREFS = "player_widget_state"

        private fun loadSnapshot(context: Context): PlayerWidgetSnapshot {
            val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return PlayerWidgetSnapshot(
                title = preferences.getString(EXTRA_TITLE, "").orEmpty(),
                artist = preferences.getString(EXTRA_ARTIST, "").orEmpty(),
                lyric = preferences.getString(EXTRA_LYRIC, "").orEmpty(),
                playing = preferences.getBoolean(EXTRA_PLAYING, false),
            )
        }

        private fun saveSnapshotIfChanged(context: Context, snapshot: PlayerWidgetSnapshot): Boolean {
            if (!shouldRefreshWidget(loadSnapshot(context), snapshot)) return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(EXTRA_TITLE, snapshot.title)
                .putString(EXTRA_ARTIST, snapshot.artist)
                .putString(EXTRA_LYRIC, snapshot.lyric)
                .putBoolean(EXTRA_PLAYING, snapshot.playing)
                .apply()
            return true
        }

        /** 广播完整显示状态；相同快照不会重复刷新组件。 */
        fun broadcastUpdate(context: Context, title: String, artist: String, lyric: String, playing: Boolean) {
            val snapshot = PlayerWidgetSnapshot(title, artist, lyric, playing)
            if (!saveSnapshotIfChanged(context, snapshot)) return
            val intent = Intent(context, PlayerWidgetProvider::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist)
                .putExtra(EXTRA_LYRIC, lyric)
                .putExtra(EXTRA_PLAYING, playing)
            context.sendBroadcast(intent)
        }

        /** 兼容旧调用方的完整快照广播入口。 */
        fun broadcastLyric(context: Context, title: String, artist: String, lyric: String, playing: Boolean) =
            broadcastUpdate(context, title, artist, lyric, playing)
    }
}
