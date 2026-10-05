package com.example.local_music_player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * DLNA 视频兼容层：容器级转封装（remux，2026-09-28 第二梯队）。
 *
 * 场景：接收端 Sink 不声明 MKV/MOV 容器（DLNA 老设备常态），但 H.264/HEVC + AAC 的
 * 编码流本身都能播——本引擎把源容器**流复制**为 MP4（不解码、不重编码，I/O 级开销），
 * 之后按普通本地文件会话投送，Range/seek/拖动全部可用。
 *
 * 边界（如实）：只搬运 MP4 兼容编码（video/avc、video/hevc + audio/mp4a-latm）；
 * 其它编码 probe 返回 false，调用方回落原格式直通。MediaMuxer 需先完整写文件才生成
 * moov（流式 MP4 不可行），因此换片需等待转封装完成（I/O 级，通常数秒~数十秒）。
 * MediaMuxer(FileDescriptor/String) 转封装本体需 API 26+，低版本由调用方守卫回落。
 */
internal class DlnaVideoRemuxer(
    private val context: Context,
    private val uri: Uri,
    private val headers: Map<String, String> = emptyMap(),
) {
    /** 源是否为「可直接转 MP4」的编码组合（H.264/HEVC 视频 + AAC 音频，音频轨可缺省）。 */
    fun probe(): Boolean {
        val extractor = MediaExtractor()
        try {
            openSource(extractor)
            var videoOk = false
            var audioOk = true
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    mime in TS_VIDEO_MIMES -> videoOk = true
                    mime.startsWith("audio/") -> if (mime !in TS_AUDIO_MIMES) audioOk = false
                }
            }
            return videoOk && audioOk
        } catch (err: Exception) {
            return false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * 阻塞转封装整轨到 [output]（MP4）。[probe] 必须先于本调用返回 true。
     * [cancelled] 在会话被取消时置真（写盘过程轮询检查，尽早让出）。
     */
    fun remuxTo(output: File, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            openSource(extractor)
            val selected = mutableListOf<Pair<Int, MediaFormat>>() // extractor trackIndex -> format
            var videoIndex = -1
            var audioIndex = -1
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime in TS_VIDEO_MIMES && videoIndex < 0) {
                    videoIndex = index
                    selected += index to format
                } else if (mime in TS_AUDIO_MIMES && audioIndex < 0) {
                    audioIndex = index
                    selected += index to format
                }
            }
            if (videoIndex < 0) throw IOException("remux 失败：没有可搬运的视频轨")
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxTrackByExtractor = mutableMapOf<Int, Int>()
            selected.forEach { (index, format) ->
                extractor.selectTrack(index)
                muxTrackByExtractor[index] = muxer.addTrack(format)
            }
            muxer.start()
            var maxSize = 64 * 1024
            selected.forEach { (_, format) ->
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0).let { if (it > maxSize) maxSize = it }
            }
            val buffer = ByteBuffer.allocate(maxSize)
            val info = MediaCodec.BufferInfo()
            while (!cancelled()) {
                buffer.rewind()
                buffer.limit(buffer.capacity())
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val extractorIndex = extractor.sampleTrackIndex
                val muxTrack = muxTrackByExtractor[extractorIndex]
                val isCodecConfig = extractor.sampleFlags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (muxTrack != null && !isCodecConfig) {
                    buffer.position(0)
                    buffer.limit(sampleSize)
                    var flags = 0
                    if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                    }
                    info.set(0, sampleSize, extractor.sampleTime, flags)
                    muxer.writeSampleData(muxTrack, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
        } finally {
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun openSource(extractor: MediaExtractor) {
        if (uri.scheme == "http" || uri.scheme == "https") {
            extractor.setDataSource(context, uri, headers)
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }

    companion object {
        private val TS_VIDEO_MIMES = setOf("video/avc", "video/hevc")
        private val TS_AUDIO_MIMES = setOf("audio/mp4a-latm")
    }
}

/**
 * 流式 TS 泵（配合 [DlnaTsMuxer]）：MediaExtractor 抽样 → H.264 AVCC 转 Annex B /
 * AAC 加 ADTS 头 → TS 打包即写 socket，**边转边推、无需等文件**（2026-09-28 流式投屏）。
 */
internal class DlnaTsStreamPump(
    private val context: Context,
    private val uri: Uri,
    private val headers: Map<String, String>,
) {
    /** 源是否可流式转 TS：H.264 视频 + AAC 音频（音频轨可缺省）。 */
    fun probePlayable(): Boolean {
        val extractor = MediaExtractor()
        try {
            open(extractor)
            var video = false
            for (index in 0 until extractor.trackCount) {
                when (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)) {
                    "video/avc" -> video = true
                    "video/hevc" -> return false
                    null -> Unit
                }
            }
            return video
        } catch (err: Exception) {
            return false
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** 阻塞泵：整轨抽样转 TS 写入 output。设备断开（write 抛错）或会话退役即结束。 */
    fun pump(output: java.io.OutputStream, cancelled: () -> Boolean) {
        val extractor = MediaExtractor()
        val counters = DlnaTsMuxer.ContinuityCounters()
        try {
            open(extractor)
            var videoIndex = -1
            var audioIndex = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                when (format.getString(MediaFormat.KEY_MIME)) {
                    "video/avc" -> if (videoIndex < 0) { videoIndex = index; videoFormat = format }
                    "audio/mp4a-latm" -> if (audioIndex < 0) { audioIndex = index; audioFormat = format }
                }
            }
            if (videoFormat == null) throw IOException("流式 TS 失败：没有 H.264 视频轨")
            val maxInput = maxOf(
                videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024),
                audioFormat?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024) ?: 64 * 1024,
            )
            val parameterSets = videoFormat.getByteBuffer("csd-0")
                ?.let { csd -> DlnaTsMuxer.avcCParameterSets(csd.toBytes()) }
            val adtsConfig = audioFormat?.getByteBuffer("csd-0")?.let { csd -> csd.toBytes() }
            extractor.selectTrack(videoIndex)
            if (audioIndex >= 0) extractor.selectTrack(audioIndex)
            output.write(DlnaTsMuxer.patSection(DlnaTsMuxer.PID_PMT))
            output.write(DlnaTsMuxer.pmtSection(DlnaTsMuxer.PID_VIDEO, DlnaTsMuxer.PID_AUDIO.takeIf { audioIndex >= 0 }))
            val buffer = ByteBuffer.allocate(maxInput)
            val view = ByteArray(maxInput)
            while (!cancelled()) {
                buffer.rewind()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val trackIndex = extractor.sampleTrackIndex
                val ptsUs = extractor.sampleTime
                val keyframe = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                buffer.position(0)
                buffer.get(view, 0, size)
                val sampleBytes = view.copyOf(size)
                val packets: List<ByteArray>
                val pid: Int
                if (trackIndex == videoIndex) {
                    var sample = DlnaTsMuxer.avccSampleToAnnexB(sampleBytes)
                    if (keyframe && parameterSets != null) sample = parameterSets + sample
                    pid = DlnaTsMuxer.PID_VIDEO
                    packets = DlnaTsMuxer.pesPayloads(DlnaTsMuxer.STREAM_ID_VIDEO, ptsUs, sample, video = true)
                } else if (trackIndex == audioIndex) {
                    val frame = adtsConfig?.let { config ->
                        DlnaTsMuxer.adtsHeader(config, size) + sampleBytes
                    } ?: sampleBytes
                    pid = DlnaTsMuxer.PID_AUDIO
                    packets = DlnaTsMuxer.pesPayloads(DlnaTsMuxer.STREAM_ID_AUDIO, ptsUs, frame, video = false)
                } else continue
                packets.forEachIndexed { index, chunk ->
                    val pcr = if (trackIndex == videoIndex && keyframe && index == 0) ptsUs * 9 / 100 else null
                    output.write(DlnaTsMuxer.tsPacket(pid, index == 0, counters.next(pid), chunk, pcr))
                }
                extractor.advance()
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun open(extractor: MediaExtractor) {
        if (uri.scheme == "http" || uri.scheme == "https") {
            extractor.setDataSource(context, uri, headers)
        } else {
            extractor.setDataSource(context, uri, null)
        }
    }
}

private fun ByteBuffer.toBytes(): ByteArray {
    val bytes = ByteArray(remaining())
    duplicate().get(bytes)
    return bytes
}
