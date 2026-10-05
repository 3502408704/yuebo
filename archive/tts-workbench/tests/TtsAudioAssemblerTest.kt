package com.example.local_music_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 音频组装器纯函数：WAV 解析（校验用）、如实拼接（标准 WAV 重写头部长度顺接、
 * 其余字节直通）。MP3 的 MediaCodec 解码为设备路径，不在 JVM 覆盖。
 * 2026-09-29 用户裁决：客户端零转码、零静音处理——旧的 PCM 裁剪/重封装路径已删除。
 *
 * 【2026-10-05 归档】随 TTS 工作台源码一并存档，不参与编译。
 */
class TtsAudioAssemblerTest {

    /** 16-bit 单声道 WAV 封装（测试夹具）。 */
    private fun wav(sampleRate: Int, samples: ShortArray): ByteArray {
        val dataBytes = samples.size * 2
        val header = ByteArray(44)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        writeLe(header, 4, 36 + dataBytes)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        writeLe(header, 16, 16)
        header[20] = 1
        header[22] = 1
        writeLe(header, 24, sampleRate)
        writeLe(header, 28, sampleRate * 2)
        header[32] = 2
        writeLe(header, 34, 16)
        "data".toByteArray(Charsets.US_ASCII).copyInto(header, 36)
        writeLe(header, 40, dataBytes)
        val body = ByteArray(dataBytes)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            body[i * 2] = (v and 0xFF).toByte()
            body[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return header + body
    }

    private fun writeLe(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    @Test
    fun wav_parse_reads_pcm_and_rejects_non_wav() {
        val parsed = parseTtsWavPcm(wav(24000, shortArrayOf(100, -200, 300)))
        assertEquals(24000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals(listOf<Short>(100, -200, 300), parsed.samples.toList())
        // 非 WAV（MP3/空字节）→ 可恢复异常
        assertThrows(TtsAudioDecodeException::class.java) { parseTtsWavPcm(ByteArray(0)) }
        assertThrows(TtsAudioDecodeException::class.java) {
            parseTtsWavPcm("ID3".toByteArray(Charsets.US_ASCII) + ByteArray(60))
        }
    }

    @Test
    fun wav_parse_wraps_truncated_and_unaligned_chunks_as_recoverable_errors() {
        val truncated = wav(24000, shortArrayOf(1, 2)).copyOf()
        // data 块声明一个超过文件范围的长度
        truncated[40] = 0x7f
        truncated[41] = 0x7f
        truncated[42] = 0x7f
        truncated[43] = 0x7f
        assertThrows(TtsAudioDecodeException::class.java) { parseTtsWavPcm(truncated) }

        val oddData = wav(24000, shortArrayOf(1, 2)).copyOf()
        oddData[40] = 1
        oddData[41] = 0
        oddData[42] = 0
        oddData[43] = 0
        assertThrows(TtsAudioDecodeException::class.java) { parseTtsWavPcm(oddData) }
    }

    @Test
    fun faithful_concat_rewrites_wav_headers_into_one_valid_wav() {
        val a = wav(24000, shortArrayOf(1, 2))
        val b = wav(24000, shortArrayOf(3, 4, 5))
        val merged = concatTtsTrackFaithful(listOf(a, b))
        // 产物仍是单个合法 WAV：data 长度 = 两段 PCM 之和，解析回读采样连续
        val parsed = parseTtsWavPcm(merged)
        assertEquals(24000, parsed.sampleRate)
        assertEquals(listOf<Short>(1, 2, 3, 4, 5), parsed.samples.toList())
    }

    @Test
    fun faithful_concat_passes_non_wav_bytes_through_verbatim() {
        val mp3A = "ID3".toByteArray(Charsets.US_ASCII) + ByteArray(50)
        val mp3B = ByteArray(40) { 0x55 }
        val merged = concatTtsTrackFaithful(listOf(mp3A, mp3B))
        // 字节流直通：产物 = 各片段原样顺接（长度不变、内容不改）
        assertEquals(mp3A.size + mp3B.size, merged.size)
        assertTrue(merged.copyOfRange(0, mp3A.size).contentEquals(mp3A))
        assertTrue(merged.copyOfRange(mp3A.size, merged.size).contentEquals(mp3B))
    }

    @Test
    fun standard_wav_detection_requires_data_chunk_right_after_header() {
        assertTrue(isStandardWavChunk(wav(24000, shortArrayOf(1))))
        // 非 WAV / 带附加块的 WAV 不按标准头顺接（走字节直通分支）
        assertTrue(!isStandardWavChunk("ID3".toByteArray(Charsets.US_ASCII) + ByteArray(60)))
    }

    @Test
    fun byte_concat_rejects_empty_chunks() {
        assertThrows(TtsAudioDecodeException::class.java) { concatTtsTrackBytes(emptyList()) }
        assertThrows(TtsAudioDecodeException::class.java) { concatTtsTrackFaithful(emptyList()) }
    }
}
