package com.example.local_music_player

import java.io.ByteArrayOutputStream

/**
 * 最小化 MPEG-TS 打包器（纯函数层，可 JVM 单测；2026-09-28 流式投屏用）。
 *
 * 只覆盖投屏实际需要的口径：H.264 视频 + AAC 音频、单节目、PTS-only（DTS=PTS，
 * 无 B 帧重排信息时的通行做法，绝大多数渲染端接受）。PAT/PMT 周期重发支持设备中途接入。
 *
 * 关键转换：MP4/MKV 的 H.264 是 AVCC（长度前缀），TS 要求 Annex B（00 00 00 01）；
 * 裸 AAC 帧在 TS 中要求 ADTS 头（参数取自 AudioSpecificConfig）。
 */
internal object DlnaTsMuxer {
    const val TS_PACKET_SIZE = 188
    const val PID_PAT = 0x0000
    const val PID_PMT = 0x1000
    const val PID_VIDEO = 0x0100
    const val PID_AUDIO = 0x0101
    const val STREAM_ID_VIDEO = 0xE0
    const val STREAM_ID_AUDIO = 0xC0

    private val CRC_TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            table[i] = c
        }
    }

    /** MPEG-2 系统 CRC-32（poly 0x04C11DB7，初值/结果取反 0xFFFFFFFF，MSB 先行）。 */
    fun mpegCrc32(data: ByteArray): Int {
        var crc = -1
        for (byte in data) {
            crc = crc shl 8 xor CRC_TABLE[(crc ushr 24 xor (byte.toInt() and 0xFF)) and 0xFF]
        }
        return crc
    }

    private fun crcBytes(section: ByteArray): ByteArray {
        val crc = mpegCrc32(section)
        return byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    /** 188 字节 TS 包：自适应字段按需（PCR/stuffing），payload 恰好填满包尾。 */
    fun tsPacket(pid: Int, payloadUnitStart: Boolean, continuity: Int,
                 payload: ByteArray, pcr90k: Long? = null): ByteArray {
        require(payload.size <= 184) { "TS 负载超长" }
        val packet = ByteArray(TS_PACKET_SIZE)
        packet[0] = 0x47
        packet[1] = ((if (payloadUnitStart) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        packet[2] = (pid and 0xFF).toByte()
        var offset = 4
        if (pcr90k != null || payload.size < 184) {
            val pcrBytes = if (pcr90k != null) 6 else 0
            val fieldLength = pcrBytes + (184 - payload.size - 1 - pcrBytes) // 标志 1 字节 + stuffing
            packet[3] = (0x30 or (continuity and 0x0F)).toByte()
            packet[offset++] = fieldLength.toByte()
            packet[offset++] = if (pcr90k != null) 0x10.toByte() else 0x00
            if (pcr90k != null) {
                var pcr = pcr90k * 300
                for (i in 5 downTo 0) {
                    packet[offset + i] = (pcr and 0xFF).toByte()
                    pcr = pcr shr 8
                }
                offset += 6
            }
            while (offset < TS_PACKET_SIZE - payload.size) packet[offset++] = 0xFF.toByte()
        } else {
            packet[3] = (0x10 or (continuity and 0x0F)).toByte()
        }
        payload.copyInto(packet, TS_PACKET_SIZE - payload.size)
        return packet
    }

    /** PAT 段：program 1 → PMT PID，含 CRC，0xFF 填充到包负载大小。 */
    fun patSection(pmtPid: Int): ByteArray {
        val section = byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, // table_id, 语法标志+长度 13
            0x00, 0x01, // transport_stream_id
            0xC1.toByte(), 0x00, 0x00, // version/current, section, last
            0x00, 0x01, // program_number = 1
            (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte(), (pmtPid and 0xFF).toByte(),
        ) + crcBytes(byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00, 0x00, 0x01,
            (0xE0 or ((pmtPid shr 8) and 0x1F)).toByte(), (pmtPid and 0xFF).toByte(),
        ))
        return ByteArray(184).also { dst ->
            section.copyInto(dst)
            dst.fill(0xFF.toByte(), section.size, dst.size)
        }
    }

    /** PMT 段：H.264 视频流 + AAC 音频流（音频可缺省），含 CRC。 */
    fun pmtSection(videoPid: Int, audioPid: Int?): ByteArray {
        val esList = mutableListOf<Byte>()
        fun elementaryStream(streamType: Int, pid: Int) {
            esList += listOf(
                streamType.toByte(),
                (0xE0 or ((pid shr 8) and 0x1F)).toByte(), (pid and 0xFF).toByte(),
                0xF0.toByte(), 0x00,
            )
        }
        elementaryStream(0x1B, videoPid)
        audioPid?.let { elementaryStream(0x0F, it) }
        val es = esList.toByteArray()
        val sectionLength = 9 + es.size + 4
        val section = ByteArray(3 + sectionLength)
        section[0] = 0x02
        section[1] = (0xB0 or ((sectionLength shr 8) and 0x1F)).toByte()
        section[2] = (sectionLength and 0xFF).toByte()
        section[3] = 0x00; section[4] = 0x01 // program_number
        section[5] = 0xC1.toByte(); section[6] = 0x00; section[7] = 0x00
        section[8] = (0xE0 or ((videoPid shr 8) and 0x1F)).toByte() // PCR PID = 视频
        section[9] = (videoPid and 0xFF).toByte()
        section[10] = 0xF0.toByte(); section[11] = 0x00
        es.copyInto(section, 12)
        crcBytes(section).copyInto(section, section.size - 4)
        return ByteArray(184).also { dst ->
            section.copyInto(dst)
            dst.fill(0xFF.toByte(), section.size, dst.size)
        }
    }

    /** PES 打包（PTS-only）：返回按 184 字节切分的负载序列（首片含 PES 头与 PUSI）。 */
    fun pesPayloads(streamId: Int, ptsUs: Long, data: ByteArray,
                    video: Boolean, splice: ByteArray? = null): List<ByteArray> {
        val pts = ptsUs * 9 / 100 and 0x1FFFFFFFFL
        val pesLength = if (video) 0 else data.size + 8
        val header = byteArrayOf(
            0x00, 0x00, 0x01, streamId.toByte(),
            (pesLength shr 8).toByte(), (pesLength and 0xFF).toByte(),
            0x80.toByte(), 0x05,
            (0x21 or ((pts shr 29).toInt() and 0x0E)).toByte(),
            ((pts shr 22) and 0xFF).toByte(),
            (0x01 or ((pts shr 14).toInt() and 0xFE)).toByte(),
            ((pts shr 7) and 0xFF).toByte(),
            (0x01 or ((pts shl 1).toInt() and 0xFE)).toByte(),
        )
        val payload = when {
            splice != null -> splice + header + data
            else -> header + data
        }
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + 184, payload.size)
            chunks += payload.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    /** AVCC（csd-0）解析 SPS/PPS → Annex B 参数集（关键帧前拼接）。 */
    fun avcCParameterSets(csd: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        if (csd.size < 7 || csd[0].toInt() != 1) return out.toByteArray()
        var offset = 6
        fun copyNals(count: Int) {
            repeat(count) {
                if (offset + 2 > csd.size) return
                val nalLength = ((csd[offset].toInt() and 0xFF) shl 8) or (csd[offset + 1].toInt() and 0xFF)
                offset += 2
                if (offset + nalLength > csd.size) return
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(csd, offset, nalLength)
                offset += nalLength
            }
        }
        copyNals(csd[5].toInt() and 0x1F)
        if (offset < csd.size) {
            val ppsCount = csd[offset].toInt() and 0xFF
            offset++
            copyNals(ppsCount)
        }
        return out.toByteArray()
    }

    /** AVCC 帧样本（长度前缀 NALU）→ Annex B。 */
    fun avccSampleToAnnexB(sample: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset + 4 <= sample.size) {
            val nalLength = ((sample[offset].toInt() and 0xFF) shl 24) or ((sample[offset + 1].toInt() and 0xFF) shl 16) or
                ((sample[offset + 2].toInt() and 0xFF) shl 8) or (sample[offset + 3].toInt() and 0xFF)
            offset += 4
            if (nalLength <= 0 || offset + nalLength > sample.size) break
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(sample, offset, nalLength)
            offset += nalLength
        }
        return out.toByteArray()
    }

    /** 从 AudioSpecificConfig（2 字节）构造 7 字节 ADTS 头。 */
    fun adtsHeader(audioSpecificConfig: ByteArray, frameLength: Int): ByteArray {
        val b0 = audioSpecificConfig.getOrElse(0) { 0 }.toInt() and 0xFF
        val b1 = audioSpecificConfig.getOrElse(1) { 0 }.toInt() and 0xFF
        val objectType = ((b0 shr 3) and 0x1F) - 1
        val freqIndex = ((b0 and 0x07) shl 1) or ((b1 shr 7) and 0x01)
        val channels = (b1 shr 3) and 0x0F
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((objectType and 0x03) shl 6) or ((freqIndex and 0x0F) shl 2) or ((channels shr 2) and 0x01)).toByte(),
            (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 0x07) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    /** 连续计数器：每个 PID 独立、模 16 递增。 */
    internal class ContinuityCounters {
        private val counters = mutableMapOf<Int, Int>()
        fun next(pid: Int): Int {
            val next = (counters.getOrDefault(pid, -1) + 1) and 0x0F
            counters[pid] = next
            return next
        }
    }
}
