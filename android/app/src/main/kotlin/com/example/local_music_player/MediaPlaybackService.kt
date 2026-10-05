package com.example.local_music_player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log

class MediaPlaybackService : Service() {
    private var controller: MediaController? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "播放控制", NotificationManager.IMPORTANCE_LOW),
            )
        }
        // 播放会话期间持有高性能 Wi-Fi 锁：锁屏后 Wi-Fi 进省电模式会把流媒体读得又慢又断
        // （尤其直播），与 TTS 合成服务（2026-09-22 实测有效）同一方案
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yuebo:playback")
            .apply { setReferenceCounted(false); acquire() }
    }

    override fun onDestroy() {
        running = false
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREVIOUS, ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_REWIND, ACTION_FAST_FORWARD -> {
                ensureController()
                when (intent.action) {
                    ACTION_PREVIOUS -> controller?.transportControls?.skipToPrevious()
                    ACTION_PLAY_PAUSE -> if (intent.getBooleanExtra(EXTRA_PLAYING, false)) controller?.transportControls?.pause() else controller?.transportControls?.play()
                    ACTION_NEXT -> controller?.transportControls?.skipToNext()
                    ACTION_REWIND -> controller?.transportControls?.rewind()
                    ACTION_FAST_FORWARD -> controller?.transportControls?.fastForward()
                }
            }
            else -> {
                val token = intent?.parcelableToken() ?: return START_NOT_STICKY
                controller = MediaController(this, token)
                startForeground(NOTIFICATION_ID, notification(intent, token))
            }
        }
        // STICKY：锁屏期间进程被杀后系统拉起服务，配合 WifiLock 保住播放会话的网络环境
        return START_STICKY
    }

    /** 从系统活跃 MediaSession 中获取 controller；用于桌面小部件等未先 show() 的场景。 */
    private fun ensureController() {
        if (controller != null) return
        runCatching {
            val sessions = (getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(null)
            controller = sessions.firstOrNull()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(intent: Intent, token: MediaSession.Token): Notification {
        val playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            .setContentText(intent.getStringExtra(EXTRA_ARTIST).orEmpty())
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(action(android.R.drawable.ic_media_rew, "快退", ACTION_REWIND, playing))
            .addAction(action(android.R.drawable.ic_media_previous, "上一首", ACTION_PREVIOUS, playing))
            .addAction(action(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (playing) "暂停" else "播放", ACTION_PLAY_PAUSE, playing))
            .addAction(action(android.R.drawable.ic_media_next, "下一首", ACTION_NEXT, playing))
            .addAction(action(android.R.drawable.ic_media_ff, "快进", ACTION_FAST_FORWARD, playing))
            .setStyle(Notification.MediaStyle().setMediaSession(token).setShowActionsInCompactView(1, 2, 3))
            .build()
    }

    private fun action(icon: Int, title: String, command: String, playing: Boolean) = Notification.Action.Builder(
        Icon.createWithResource(this, icon),
        title,
        PendingIntent.getService(
            this,
            command.hashCode(),
            Intent(this, MediaPlaybackService::class.java).setAction(command).putExtra(EXTRA_PLAYING, playing),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    @Suppress("DEPRECATION")
    private fun Intent.parcelableToken(): MediaSession.Token? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_TOKEN, MediaSession.Token::class.java)
    } else getParcelableExtra(EXTRA_TOKEN)

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        const val EXTRA_PLAYING = "playing"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_PLAY_PAUSE = "play_pause"
        const val ACTION_NEXT = "next"
        private const val ACTION_REWIND = "rewind"
        private const val ACTION_FAST_FORWARD = "fast_forward"
        private const val TAG = "MediaPlaybackService"

        /** 进程内标记：服务已启动后后续刷新通知走普通 startService，不再发起前台服务启动。 */
        @Volatile
        private var running = false

        fun show(context: Context, session: MediaSession, track: NativeTrack, playing: Boolean) {
            val intent = Intent(context, MediaPlaybackService::class.java)
                .putExtra(EXTRA_TOKEN, session.sessionToken)
                .putExtra(EXTRA_TITLE, track.title)
                .putExtra(EXTRA_ARTIST, track.artist)
                .putExtra(EXTRA_PLAYING, playing)
            startPlaybackService(context, intent)
        }

        /**
         * 只在服务尚未运行时走 startForegroundService，避免把前台服务的首次启动留在锁屏/退后台的
         * ON_PAUSE 里（Android 12+ 会抛 ForegroundServiceStartNotAllowedException）。
         */
        private fun startPlaybackService(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !running) {
                try {
                    context.startForegroundService(intent)
                    return
                } catch (error: Throwable) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && error is ForegroundServiceStartNotAllowedException) {
                        Log.w(TAG, "后台启动前台播放服务被拒绝，退化为普通启动", error)
                    } else {
                        Log.w(TAG, "启动前台播放服务失败，退化为普通启动", error)
                    }
                }
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "刷新播放通知失败", it) }
        }
    }
}
