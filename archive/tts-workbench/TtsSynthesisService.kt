package com.example.local_music_player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TTS 合成前台服务（dataSync）：合成任务本体跑在应用级协程（TtsWorkbenchState），
 * 本服务只做「保活壳」——前台通知 + WakeLock/WifiLock，让锁屏/切后台后系统不掐
 * 网络不冻进程（合成是分钟级多段请求，后台一抖一段就丢，vivo 等深度管控机型尤甚，
 * 2026-09-22 用户实测「手机必须保持前台，后台立刻任务失败」）。
 * running 转假即自行释放锁并停止；进程被杀时任务本就随之消亡，无需 STICKY 恢复。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，不参与编译。
 */
class TtsSynthesisService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val app: MusicApplication get() = application as MusicApplication
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "语音合成", NotificationManager.IMPORTANCE_LOW),
            )
        }
        acquireLocks()
        // 30 分钟锁到期前自动续期：整本有声书合成可超 30 分钟，续期失败（极端省电策略）
        // 才退化为系统默认行为；任务结束由 running 观察分支释放锁并停服务
        scope.launch {
            while (app.ttsWorkbenchState.running) {
                kotlinx.coroutines.delay(LOCK_RENEW_INTERVAL_MS)
                if (!app.ttsWorkbenchState.running) break
                wakeLock?.takeIf { it.isHeld }?.acquire(LOCK_TIMEOUT_MS)
                    ?: acquireLocks()
            }
        }
        scope.launch {
            snapshotFlow { Triple(app.ttsWorkbenchState.running, app.ttsWorkbenchState.progress, app.ttsWorkbenchState.status) }
                .collect { (running, progress, status) ->
                    if (running) {
                        startForeground(NOTIFICATION_ID, notification(progress.ifBlank { status }))
                    } else {
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(app.ttsWorkbenchState.progress.ifBlank { app.ttsWorkbenchState.status }))
        // 保活壳无命令语义；任务取消/完成由 running 观察分支自行停服务
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yuebo:tts_synthesis")
            .apply { setReferenceCounted(false); acquire(LOCK_TIMEOUT_MS) }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(WifiManager::class.java))
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yuebo:tts_synthesis")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在合成语音轨道")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tts_synthesis"
        private const val NOTIFICATION_ID = 4
        private const val LOCK_TIMEOUT_MS = 30 * 60 * 1000L  // 单次合成上限 30 分钟，防泄漏兜底
        private const val LOCK_RENEW_INTERVAL_MS = 10 * 60 * 1000L  // 每 10 分钟续期一次，长任务不受 30 分钟硬上限

        fun start(context: Context) {
            val intent = Intent(context, TtsSynthesisService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
