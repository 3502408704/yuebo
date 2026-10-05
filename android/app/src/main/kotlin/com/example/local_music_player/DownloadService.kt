package com.example.local_music_player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 下载前台服务：保活 [DownloadEngine]，聚合展示在线音乐、百度网盘与夸克网盘的下载进度。 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val app: MusicApplication get() = application as MusicApplication

    /** 三族下载任务的聚合视图：前台通知与「是否还有活动任务」判定共用。 */
    private data class DownloadOverview(
        val tasks: List<DownloadTask>,
        val panTasks: List<BaiduPanDownload>,
        val quarkTasks: List<QuarkPanDownload>,
    ) {
        fun unfinishedCount(): Int = tasks.count { it.status in ACTIVE_STATUSES } +
            panTasks.count { it.status in ACTIVE_STATUSES } +
            quarkTasks.count { it.status in ACTIVE_STATUSES }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "下载任务", NotificationManager.IMPORTANCE_LOW),
            )
        }
        scope.launch {
            app.downloads.recoverAfterServiceRestart()
            app.panDownloads.recoverAfterServiceRestart()
            app.quarkDownloads.recoverAfterServiceRestart()
            app.downloadEngine.reconcile()
        }
        scope.launch {
            combine(
                app.downloads.tasks,
                app.panDownloads.downloads,
                app.quarkDownloads.downloads,
            ) { tasks, panTasks, quarkTasks ->
                DownloadOverview(tasks, panTasks, quarkTasks)
            }.collect { overview ->
                val unfinished = overview.unfinishedCount() > 0
                if (unfinished) {
                    startForeground(NOTIFICATION_ID, notification(overview))
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(DownloadOverview(emptyList(), emptyList(), emptyList())))
        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_TASK_ID)?.let(app.downloadEngine::pauseOnline)
            ACTION_RESUME -> intent.getStringExtra(EXTRA_TASK_ID)?.let(app.downloadEngine::resumeOnline)
            ACTION_RETRY -> intent.getStringExtra(EXTRA_TASK_ID)?.let(app.downloadEngine::retryOnline)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_TASK_ID)?.let(app.downloadEngine::cancelOnline)
            ACTION_PAN_PAUSE -> intent.getLongExtra(EXTRA_FS_ID, -1L).takeIf { it > 0 }?.let(app.downloadEngine::pausePan)
            ACTION_PAN_RESUME -> intent.getLongExtra(EXTRA_FS_ID, -1L).takeIf { it > 0 }?.let(app.downloadEngine::resumePan)
            ACTION_PAN_CANCEL -> intent.getLongExtra(EXTRA_FS_ID, -1L).takeIf { it > 0 }?.let(app.downloadEngine::cancelPan)
            ACTION_QUARK_PAUSE -> intent.getStringExtra(EXTRA_FID)?.let(app.downloadEngine::pauseQuark)
            ACTION_QUARK_RESUME -> intent.getStringExtra(EXTRA_FID)?.let(app.downloadEngine::resumeQuark)
            ACTION_QUARK_CANCEL -> intent.getStringExtra(EXTRA_FID)?.let(app.downloadEngine::cancelQuark)
            else -> scope.launch { app.downloadEngine.reconcile() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(overview: DownloadOverview): Notification {
        val active = overview.tasks.filter { it.status in ACTIVE_STATUSES }
        val panActive = overview.panTasks.filter { it.status in ACTIVE_STATUSES }
        val quarkActive = overview.quarkTasks.filter { it.status in ACTIVE_STATUSES }
        val count = active.size + panActive.size + quarkActive.size
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val total = (active.map { it.totalBytes } + panActive.map { it.size } + quarkActive.map { it.size })
            .takeIf { it.isNotEmpty() && it.all { value -> value > 0 } }?.sum() ?: 0L
        val percent = if (total > 0) {
            val downloaded = active.sumOf { it.downloadedBytes } + panActive.sumOf { it.downloadedBytes } +
                quarkActive.sumOf { it.downloadedBytes }
            ((downloaded * 100) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (count == 0) "准备下载" else "正在下载 $count 项")
            .setContentText(if (total > 0) "$percent%" else "正在连接")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, total <= 0)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_FS_ID = "fs_id"
        private const val EXTRA_FID = "fid"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_RETRY = "retry"
        private const val ACTION_CANCEL = "cancel"
        private const val ACTION_PAN_PAUSE = "pan_pause"
        private const val ACTION_PAN_RESUME = "pan_resume"
        private const val ACTION_PAN_CANCEL = "pan_cancel"
        private const val ACTION_QUARK_PAUSE = "quark_pause"
        private const val ACTION_QUARK_RESUME = "quark_resume"
        private const val ACTION_QUARK_CANCEL = "quark_cancel"

        private val ACTIVE_STATUSES = setOf(
            DownloadTaskStatus.QUEUED,
            DownloadTaskStatus.DOWNLOADING,
            DownloadTaskStatus.WAITING_NETWORK,
            DownloadTaskStatus.FINALIZING,
        )

        fun start(context: Context) = send(context, null, null, -1L, null)

        fun pause(context: Context, id: String) = send(context, ACTION_PAUSE, id, -1L, null)

        fun resume(context: Context, id: String) = send(context, ACTION_RESUME, id, -1L, null)

        fun retry(context: Context, id: String) = send(context, ACTION_RETRY, id, -1L, null)

        fun cancel(context: Context, id: String) = send(context, ACTION_CANCEL, id, -1L, null)

        fun panPause(context: Context, fsId: Long) = send(context, ACTION_PAN_PAUSE, null, fsId, null)

        fun panResume(context: Context, fsId: Long) = send(context, ACTION_PAN_RESUME, null, fsId, null)

        fun panCancel(context: Context, fsId: Long) = send(context, ACTION_PAN_CANCEL, null, fsId, null)

        fun quarkPause(context: Context, fid: String) = send(context, ACTION_QUARK_PAUSE, null, -1L, fid)

        fun quarkResume(context: Context, fid: String) = send(context, ACTION_QUARK_RESUME, null, -1L, fid)

        fun quarkCancel(context: Context, fid: String) = send(context, ACTION_QUARK_CANCEL, null, -1L, fid)

        private fun send(context: Context, action: String?, id: String?, fsId: Long, fid: String?) {
            val intent = Intent(context, DownloadService::class.java).apply {
                this.action = action
                if (id != null) putExtra(EXTRA_TASK_ID, id)
                if (fsId > 0) putExtra(EXTRA_FS_ID, fsId)
                if (fid != null) putExtra(EXTRA_FID, fid)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
