package com.example.local_music_player

import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * 主线程转发层：Media3/ExoPlayer 强制所有方法在主线程访问，而 VM 自 BASS 时代起
 * 在 Dispatchers.IO 等后台线程也会调用播放器（如网盘/夸克流的 `withContext(IO){ player.playUrl() }`）。
 * 本层把所有 [AudioEngine] 调用同步转发到主线程再执行，避免 IO 线程触碰 ExoPlayer
 * 抛 `IllegalStateException` 被调用方 runCatching 静默吞掉（表现为无声无日志地失败）。
 *
 * 死锁前提：后台线程等待期间主线程处于空闲协程挂起（VM 使用 launch + withContext 切 IO，
 * 主线程不阻塞等待），故 postAndWait 不会互等。
 */
internal class MainThreadAudioEngine(private val delegate: AudioEngine) : AudioEngine {
    private val mainHandler = Handler(Looper.getMainLooper())

    private inline fun <T> onMainThread(crossinline block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<Any?>()
        val error = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(block())
            } catch (t: Throwable) {
                error.set(t)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error.get()?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result.get() as T
    }

    override fun play(track: NativeTrack, positionMs: Long) = onMainThread { delegate.play(track, positionMs) }
    override fun playUrl(url: String, durationMs: Long, positionMs: Long,
                         headers: Map<String, String>, isLive: Boolean) =
        onMainThread { delegate.playUrl(url, durationMs, positionMs, headers, isLive) }

    override fun pause(): Boolean = onMainThread { delegate.pause() }
    override fun resume(): Boolean = onMainThread { delegate.resume() }
    override fun seek(positionMs: Long): Boolean = onMainThread { delegate.seek(positionMs) }
    override fun positionMs(): Long = onMainThread { delegate.positionMs() }
    override fun durationMs(): Long = onMainThread { delegate.durationMs() }
    override fun lyrics(): String? = onMainThread { delegate.lyrics() }
    override fun isPlaying(): Boolean = onMainThread { delegate.isPlaying() }
    override fun isStopped(): Boolean = onMainThread { delegate.isStopped() }

    override fun setVolume(value: Float): Boolean = onMainThread { delegate.setVolume(value) }
    override fun setSpeed(value: Double): Boolean = onMainThread { delegate.setSpeed(value) }
    override fun setFadeEnabled(enabled: Boolean) = onMainThread { delegate.setFadeEnabled(enabled) }
    override fun setSilenceSkipping(enabled: Boolean, mode: SilenceSkipMode, thresholdMs: Long) =
        onMainThread { delegate.setSilenceSkipping(enabled, mode, thresholdMs) }

    override fun setPlaybackCompleteListener(listener: () -> Unit) =
        onMainThread { delegate.setPlaybackCompleteListener(listener) }

    override fun setFocusLossListener(listener: () -> Unit) = onMainThread { delegate.setFocusLossListener(listener) }
    override fun fadeOut(onComplete: () -> Unit): Boolean = onMainThread { delegate.fadeOut(onComplete) }
    override fun setPreferredDevice(device: AudioDeviceInfo?): Boolean = onMainThread { delegate.setPreferredDevice(device) }
    override fun hasAudioTrack(): Boolean = onMainThread { delegate.hasAudioTrack() }
    override fun routedDevice(): AudioDeviceInfo? = onMainThread { delegate.routedDevice() }
    override fun setRouteListener(listener: (AudioDeviceInfo?) -> Unit) = onMainThread { delegate.setRouteListener(listener) }

    override fun setEq(bands: FloatArray, preamp: Float, enabled: Boolean) =
        onMainThread { delegate.setEq(bands, preamp, enabled) }

    override fun setEffects(effects: AudioEffects) = onMainThread { delegate.setEffects(effects) }
    override fun release() = onMainThread { delegate.release() }
}
