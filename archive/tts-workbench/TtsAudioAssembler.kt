package com.example.local_music_player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.VisibleForTesting
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * TTS 音频组装器（2026-09-29 用户裁决后收敛）：
 * 片段合成/断点恢复时已逐段解码校验；整轨**如实拼接**——同为标准 WAV 时重写头部长度顺接，
 * 其余（mp3 等字节流）按字节直通。客户端不做 PCM 转码、不裁静音、不改格式。
 * 解码仅用于片段有效性校验；失败抛 [TtsAudioDecodeException]（可恢复：调用方重试缺失片段）。
 *
 * 【2026-10-05 归档】月播最终版移除服务端在线服务，本目录为 TTS 工作台源码存档，不参与编译。
 */

/** 可恢复的音频处理错误（某设备解不了某个片段/格式漂移）：不产出未处理的拼接文件。 */
internal class TtsAudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 统一 16-bit PCM（交错声道；采样率/声道数取自首个片段，整轨一致）。仅片段校验使用。 */
internal class TtsPcm(val sampleRate: Int, val channels: Int, val samples: ShortArray)

// ==== 拼接：如实顺接，零转码 ====

/** 压缩/非标准格式直通拼轨：按脚本顺序字节顺接，不转码、不改容器。 */
internal fun concatTtsTrackBytes(chunks: List<ByteArray>): ByteArray {
    if (chunks.isEmpty()) throw TtsAudioDecodeException("音频分段为空")
    val out = ByteArrayOutputStream(chunks.sumOf { it.size })
    chunks.forEach { out.write(it) }
    return out.toByteArray()
}

/** 标准 44 字节头 WAV 判定：RIFF/WAVE 且 data 块紧跟头部（data 长度=文件长-44）。 */
internal fun isStandardWavChunk(chunk: ByteArray): Boolean {
    if (!ttsChunkIsWav(chunk) || chunk.size < 44) return false
    val dataLength = (chunk[40].toInt() and 0xFF) or ((chunk[41].toInt() and 0xFF) shl 8) or
        ((chunk[42].toInt() and 0xFF) shl 16) or ((chunk[43].toInt() and 0xFF) shl 24)
    return dataLength == chunk.size - 44
}

/**
 * 角色整轨如实拼接：全部为标准 44 字节头 WAV 时重写头部长度顺接（concatTtsWav，产物仍为合法 WAV）；
 * 否则（mp3 等字节流）按字节直通。任何路径都不解码、不裁静音、不改采样率。
 */
internal fun concatTtsTrackFaithful(chunks: List<ByteArray>): ByteArray {
    if (chunks.isEmpty()) throw TtsAudioDecodeException("音频分段为空")
    return if (chunks.all { isStandardWavChunk(it) }) concatTtsWav(chunks) else concatTtsTrackBytes(chunks)
}

// ==== 解码：仅用于片段有效性校验（WAV 直接解析 PCM；MP3 走平台 MediaCodec，JVM 测试只覆盖纯函数） ====

private fun leInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or ((bytes[offset + 3].toInt() and 0xFF) shl 24)

private fun leShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

/**
 * 解析 WAV 的 PCM（fmt/data 块按 RIFF 布局定位，容忍 LIST 等附加块）；
 * 只接受 16-bit PCM（上游恒为 16-bit；位深不符抛可恢复异常）。
 */
@VisibleForTesting
internal fun parseTtsWavPcm(bytes: ByteArray): TtsPcm {
    try {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw TtsAudioDecodeException("WAV 文件头无效")
        }
        var fmt: Pair<Int, Int>? = null  // sampleRate to channels
        var bits: Int? = null
        var dataStart = -1
        var dataSize = 0
        var offset = 12
        while (offset <= bytes.size - 8) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val declaredSize = leInt(bytes, offset + 4).toLong()
            if (declaredSize < 0) throw TtsAudioDecodeException("WAV 块长度无效")
            val body = offset + 8
            val chunkEnd = body.toLong() + declaredSize
            val paddedEnd = chunkEnd + (declaredSize and 1L)
            if (chunkEnd > bytes.size || paddedEnd > bytes.size) {
                throw TtsAudioDecodeException("WAV 块超出文件范围")
            }
            when (id) {
                "fmt " -> {
                    if (declaredSize < 16 || body.toLong() + 16 > chunkEnd) {
                        throw TtsAudioDecodeException("WAV fmt 块无效")
                    }
                    val audioFormat = leShort(bytes, body)
                    if (audioFormat != 1) {
                        throw TtsAudioDecodeException("仅支持 PCM 编码（format=$audioFormat）")
                    }
                    val channels = leShort(bytes, body + 2)
                    val sampleRate = leInt(bytes, body + 4)
                    val depth = leShort(bytes, body + 14)
                    if (channels !in 1..2 || sampleRate !in 8000..96000 || depth <= 0) {
                        throw TtsAudioDecodeException("WAV 音频参数不支持（$sampleRate Hz/$channels 声道）")
                    }
                    fmt = sampleRate to channels
                    bits = depth
                }
                "data" -> {
                    if (declaredSize > 0 && dataStart < 0) {
                        dataStart = body
                        dataSize = declaredSize.toInt()
                    }
                }
            }
            offset = paddedEnd.toInt()  // RIFF 块按字对齐
        }
        val (sampleRate, channels) = fmt ?: throw TtsAudioDecodeException("WAV 缺少 fmt 块")
        if (dataStart < 0 || dataSize <= 0) throw TtsAudioDecodeException("WAV 缺少 data 块")
        if (bits != 16) throw TtsAudioDecodeException("仅支持 16-bit WAV（当前 ${bits ?: 0} bit）")
        if (dataSize and 1 != 0) throw TtsAudioDecodeException("WAV PCM 数据长度不是偶数")
        val pcmBytes = bytes.copyOfRange(dataStart, dataStart + dataSize)
        val sampleCount = pcmBytes.size / 2
        if (sampleCount == 0) throw TtsAudioDecodeException("WAV PCM 数据为空")
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(sampleCount)
        for (i in 0 until sampleCount) samples[i] = buffer.short
        return TtsPcm(sampleRate, channels, samples)
    } catch (failure: TtsAudioDecodeException) {
        throw failure
    } catch (failure: Exception) {
        throw TtsAudioDecodeException("WAV 解析失败（${failure.javaClass.simpleName}）", failure)
    }
}

/**
 * 单片段解码为统一 PCM（仅校验用）：WAV 直接解析；MP3 经 MediaCodec（写临时文件喂
 * MediaExtractor，解码输出收集为 16-bit PCM；浮点输出转 16-bit）。失败一律抛
 * [TtsAudioDecodeException]。
 */
internal fun decodeTtsChunkToPcm(context: Context, bytes: ByteArray): TtsPcm {
    if (ttsChunkIsWav(bytes)) return parseTtsWavPcm(bytes)
    return decodeMp3WithMediaCodec(context, bytes)
}

private fun decodeMp3WithMediaCodec(context: Context, bytes: ByteArray): TtsPcm {
    val temp = File(context.cacheDir, "tts_chunk_${System.nanoTime()}.mp3")
    try {
        temp.writeBytes(bytes)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(temp.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw TtsAudioDecodeException("音频文件中没有可解码的轨道")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw TtsAudioDecodeException("音频轨道类型未知")
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val collected = ShortArrayOutputStream()
                var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                }
                val info = MediaCodec.BufferInfo()
                var sawInputEos = false
                var sawOutputEos = false
                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inputIndex = codec.dequeueInputBuffer(10_000L)
                        if (inputIndex >= 0) {
                            val input = codec.getInputBuffer(inputIndex)
                            if (input == null) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                            } else {
                                input.clear()
                                val size = extractor.readSampleData(input, 0)
                                if (size < 0) {
                                    codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    sawInputEos = true
                                } else {
                                    codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }
                    val outputIndex = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outputIndex >= 0 -> {
                            val output = codec.getOutputBuffer(outputIndex)
                            if (output != null && info.size > 0) {
                                if (pcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                                    collected.appendFloat(output, info.offset, info.size)
                                } else {
                                    collected.appendShort(output, info.offset, info.size)
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                        }
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val changed = codec.outputFormat
                            sampleRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                pcmEncoding = changed.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            }
                        }
                    }
                }
                if (collected.size() == 0) throw TtsAudioDecodeException("音频解码未产出 PCM")
                return TtsPcm(sampleRate, channels, collected.toShortArray())
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    } catch (failure: TtsAudioDecodeException) {
        throw failure
    } catch (failure: Exception) {
        throw TtsAudioDecodeException("音频解码失败（${failure.javaClass.simpleName}）", failure)
    } finally {
        temp.delete()
    }
}

/** 线程不安全的短整型收集器（解码在单线程内完成）；接受 16-bit 与浮点两种解码输出。 */
private class ShortArrayOutputStream {
    private var buffer = ShortArray(1 shl 16)
    private var count = 0

    fun size(): Int = count

    fun appendShort(source: ByteBuffer, offset: Int, byteCount: Int) {
        val start = offset.coerceIn(0, source.limit())
        val end = (start + byteCount).coerceAtMost(source.limit())
        if (end <= start) return
        ensureCapacity(count + (end - start) / 2)
        val dup = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(start)
        dup.limit(end)
        while (dup.position() < dup.limit()) buffer[count++] = dup.short
    }

    fun appendFloat(source: ByteBuffer, offset: Int, byteCount: Int) {
        val start = offset.coerceIn(0, source.limit())
        val end = (start + byteCount).coerceAtMost(source.limit())
        if (end <= start) return
        ensureCapacity(count + (end - start) / 4)
        val dup = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        dup.position(start)
        dup.limit(end)
        while (dup.position() < dup.limit()) {
            val v = dup.float
            buffer[count++] = (v.coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var size = buffer.size
        while (size < needed) size = size shl 1
        buffer = buffer.copyOf(size)
    }

    fun toShortArray(): ShortArray = buffer.copyOf(count)
}
