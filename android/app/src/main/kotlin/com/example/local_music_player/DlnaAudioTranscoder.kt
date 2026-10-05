package com.example.local_music_player

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DLNA 投送格式兼容层的音频转码器（2026-09-28 第一梯队 1）。
 *
 * 场景：接收端 Sink 列表不声明 FLAC/ALAC 等格式时，投过去解不了——本会话内把本地音频
 * 实时解码为 16-bit PCM WAV 流式写出（WAV 是 DLNA 音频渲染端兼容性最好的口径）。
 * 边解边写、不整轨驻内存：FLAC 实时解码 CPU 占用极低，手机端可长时间运行。
 *
 * 边界（如实）：解码用系统 MediaExtractor/MediaCodec——FLAC/ALAC/MP3/AAC 等系统可解的
 * 格式生效；APE/WV 等系统不识别的格式在探测阶段即失败（决策方先 [probe] 预探测，
 * 失败则回落原格式直通，不比现状差）。转码会话不支持 Range seek（无 Accept-Ranges）。
 */
internal class DlnaAudioTranscoder(
    private val context: Context,
    private val uri: Uri,
    private val output: OutputStream,
) {
    /**
     * 预探测：源可解码时返回 (采样率, 声道数, 总毫秒)；不可解/无音轨/时长未知返回 null
     * （调用方据此放弃转码，回落直通）。
     */
    fun probe(): Triple<Int, Int, Long>? {
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(context, uri, null)
            val index = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: return null
            val format = extractor.getTrackFormat(index)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return Triple(sampleRate, channels, durationMs)
        } catch (err: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 阻塞解码整轨并写入 output（[probe] 必须先于本调用成功，参数由其提供）。
     * [cancelled] 在设备断开时由服务线程置真；任何失败抛 IOException，由代理收尾关连接。
     * 返回写入的总字节数（含 44 字节 WAV 头）。
     */
    fun transcodeWav(sampleRate: Int, channels: Int, durationMs: Long, cancelled: () -> Boolean): Long {
        val expectedDataBytes = durationMs * sampleRate * channels * 2L / 1000L
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw IOException("转码失败：没有音频轨道")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val codecMime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("转码失败：音轨类型未知")
            val decoder = MediaCodec.createDecoderByType(codecMime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            writeWavHeader(sampleRate, channels, expectedDataBytes)
            var written = 0L
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos) {
                if (cancelled()) throw IOException("转码会话已取消")
                if (!sawInputEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (buffer == null) -1 else extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = decoder.outputFormat
                        val encoding = if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            written += writePcmChunk(outputBuffer, info, floatPcm)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                    }
                }
            }
            return WAV_HEADER_BYTES + written
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun writePcmChunk(buffer: ByteBuffer, info: MediaCodec.BufferInfo, floatPcm: Boolean): Long {
        val slice = buffer.duplicate().apply {
            position(info.offset)
            limit(info.offset + info.size)
            order(ByteOrder.LITTLE_ENDIAN)
        }
        if (floatPcm) {
            // Float PCM（-1..1）转 16-bit little-endian
            val shorts = ByteArray(info.size / 2)
            var index = 0
            while (slice.remaining() >= 4) {
                val sample = slice.float.coerceIn(-1f, 1f)
                val value = (sample * Short.MAX_VALUE).toInt().toShort()
                shorts[index++] = (value.toInt() and 0xFF).toByte()
                shorts[index++] = (value.toInt() shr 8 and 0xFF).toByte()
            }
            output.write(shorts, 0, index)
            return index.toLong()
        }
        // MediaCodec 音频解码输出本身是 little-endian 原始 16-bit PCM，WAV 同序，直接写出
        val payload = ByteArray(info.size)
        slice.get(payload)
        output.write(payload)
        return payload.size.toLong()
    }

    private fun writeWavHeader(sampleRate: Int, channels: Int, dataBytes: Long) {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val header = ByteBuffer.allocate(WAV_HEADER_BYTES.toInt()).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1) // PCM
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * bitsPerSample / 8).toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        output.write(header.array())
    }

    companion object {
        internal const val WAV_HEADER_BYTES = 44L
    }
}

/** 探测阶段的空输出（probe 不产生数据）。 */
internal object DlnaTranscodeDiscardStream : OutputStream() {
    override fun write(b: Int) = Unit
    override fun write(b: ByteArray, off: Int, len: Int) = Unit
}
