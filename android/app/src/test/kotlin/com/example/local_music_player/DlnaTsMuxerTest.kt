package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MPEG-TS 打包器（DlnaTsMuxer）：包结构、CRC、AnnexB/ADTS 转换。 */
class DlnaTsMuxerTest {
    @Test
    fun mpegCrc32MatchesKnownCheckValue() {
        // CRC-32/MPEG-2 标准校验值："0123456789" → 0x0376E6E7
        assertEquals(0x0376E6E7, DlnaTsMuxer.mpegCrc32("123456789".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun tsPacketStructureIsConformant() {
        val payload = ByteArray(184) { 0x55 }
        val packet = DlnaTsMuxer.tsPacket(DlnaTsMuxer.PID_VIDEO, payloadUnitStart = true, continuity = 3, payload)
        assertEquals(188, packet.size)
        assertEquals(0x47, packet[0].toInt() and 0xFF)
        // PUSI 位 + PID 高 5 位（0x100 → 0x40 | 0x01）
        assertEquals(0x41, packet[1].toInt() and 0xFF)
        assertEquals(0x00, packet[2].toInt())
        assertEquals(0x13, packet[3].toInt()) // AFC=01（无自适应）+ CC=3
        assertEquals(0x55, packet[4].toInt())
        val withPcr = DlnaTsMuxer.tsPacket(DlnaTsMuxer.PID_VIDEO, false, 0, ByteArray(170), pcr90k = 1000L)
        assertEquals(0x30, withPcr[3].toInt()) // AFC=11 + CC=0
        assertEquals(188, withPcr.size)
    }

    @Test
    fun patAndPmtSectionsCarryPrograms() {
        val pat = DlnaTsMuxer.patSection(DlnaTsMuxer.PID_PMT)
        assertEquals(184, pat.size)
        assertTrue(pat[0].toInt() == 0x00) // table_id
        assertTrue(pat.size >= 13)
        val pmt = DlnaTsMuxer.pmtSection(DlnaTsMuxer.PID_VIDEO, DlnaTsMuxer.PID_AUDIO)
        assertEquals(184, pmt.size)
        assertEquals(0x02, pmt[0].toInt())
    }

    @Test
    fun pesCarriesPtsAndSplitsToTsSizedChunks() {
        val payloads = DlnaTsMuxer.pesPayloads(
            DlnaTsMuxer.STREAM_ID_VIDEO, ptsUs = 1_000_000, data = ByteArray(500), video = true,
        )
        assertTrue(payloads.dropLast(1).all { it.size == 184 })
        assertTrue(payloads.last().size in 1..184)
        // 首片含 PES 起始码与 PTS 标志
        assertTrue(payloads.first().copyOfRange(4, 14).size == 10)
        val audioPayloads = DlnaTsMuxer.pesPayloads(
            DlnaTsMuxer.STREAM_ID_AUDIO, ptsUs = 0, data = ByteArray(100), video = false,
        )
        assertTrue(audioPayloads.first().size <= 184)
    }

    @Test
    fun avccSampleConvertsToAnnexB() {
        // 两个 4 字节长度前缀 NALU（长度 3 与 2）
        val avcc = byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 9)
        val annexB = DlnaTsMuxer.avccSampleToAnnexB(avcc)
        assertEquals(4 + 3 + 4 + 2, annexB.size)
        assertEquals(0, annexB[0].toInt()); assertEquals(0, annexB[1].toInt())
        assertEquals(0, annexB[2].toInt()); assertEquals(1, annexB[3].toInt())
    }

    @Test
    fun adtsHeaderEncodesProfileAndLength() {
        // AudioSpecificConfig: AAC-LC(1), 44.1k(freqIndex=4), 双声道
        val config = byteArrayOf(0x12, 0x10)
        val header = DlnaTsMuxer.adtsHeader(config, frameLength = 371)
        assertEquals(0xFF.toByte(), header[0])
        assertEquals(0xF1.toByte(), header[1])
        // 长度低 8 位 + 高位混合（371 = 0b1_0111_0011）
        assertEquals((371 shr 3) and 0xFF, header[4].toInt() and 0xFF)
    }
}
