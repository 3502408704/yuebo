package com.example.local_music_player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoSubtitleTest {
    @Test
    fun parsesSrtAndFindsActiveCue() {
        val cues = parseVideoSubtitles(
            "episode.srt",
            """1
00:00:01,250 --> 00:00:03,500
<i>第一句</i>

2
00:00:04.000 --> 00:00:05.000
第二句
""",
        )

        assertEquals(2, cues.size)
        assertEquals("第一句", cues[0].text)
        assertEquals(1_250, cues[0].startMs)
        assertEquals(0, activeVideoSubtitleIndex(cues, 1_250))
        assertEquals(-1, activeVideoSubtitleIndex(cues, 3_500))
        assertEquals(1, activeVideoSubtitleIndex(cues, 4_500))
    }

    @Test
    fun parsesAssAndLrc() {
        val ass = parseVideoSubtitles(
            "episode.ass",
            "Dialogue: 0,0:00:01.00,0:00:02.50,Default,,,,,,{\\i1}你好\\N世界",
        )
        assertEquals(1, ass.size)
        assertEquals("你好\n世界", ass.single().text)
        assertEquals(1_000, ass.single().startMs)

        val lrc = parseVideoSubtitles("episode.lrc", "[00:01.00]第一句\n[00:03.50]第二句")
        assertEquals(2, lrc.size)
        assertTrue(lrc[0].endMs == lrc[1].startMs)
    }

    @Test
    fun parsesTtmlClockOffsetDurationAndLineBreaks() {
        val cues = parseVideoSubtitles(
            "episode.ttml",
            """<?xml version="1.0" encoding="UTF-8"?>
                <tt xmlns="http://www.w3.org/ns/ttml">
                  <body><div>
                    <p begin="1.5s" dur="1s"><span>第一行</span><br/>第二行</p>
                    <p begin="00:00:03.000" end="00:00:04.250">第三句</p>
                  </div></body>
                </tt>""".trimIndent(),
        )

        assertEquals(2, cues.size)
        assertEquals(VideoSubtitleCue(1_500, 2_500, "第一行\n第二行"), cues[0])
        assertEquals(VideoSubtitleCue(3_000, 4_250, "第三句"), cues[1])
    }

    @Test
    fun exportsWebVttAndPlainText() {
        val cues = listOf(
            VideoSubtitleCue(0, 1_250, "第一句\n第二行"),
            VideoSubtitleCue(3_000, 4_000, "a --> b"),
        )

        // 用转义字符串拼接，避免原始字符串里的 \u 序列不被解释导致期望值与导出结果不一致。
        assertEquals(
            "WEBVTT\n\n" +
                "00:00:00.000 --> 00:00:01.250\n" +
                "第一句\n第二行\n\n" +
                "00:00:03.000 --> 00:00:04.000\n" +
                "a -> b\n",
            VideoSubtitleExporter.toWebVtt(cues),
        )
        assertEquals("第一句\n第二行\na --> b", VideoSubtitleExporter.toPlainText(cues))
    }

    @Test
    fun parsesBilibiliSubtitleTracksAndDropsDuplicateAiTrack() {
        val result = parseBilibiliSubtitleTracks(
            """
            {"code":0,"data":{"need_login_subtitle":false,"subtitle":{"allow_submit":false,"subtitles":[
              {"id":1,"id_str":"1","lan":"zh-CN","lan_doc":"中文（简体）","subtitle_url":"//comment.bilibili.com/1.json","ai_type":0,"is_lock":false,"author":{"name":"字幕君"}},
              {"id":2,"id_str":"2","lan":"ai-zh","lan_doc":"中文（自动生成）","subtitle_url":"https://aisubtitle.hdslb.com/bfs/ai_subtitle/2.json","ai_type":1,"is_lock":false},
              {"id":3,"id_str":"3","lan":"en-US","lan_doc":"English","subtitle_url":"//comment.bilibili.com/3.json","ai_type":0,"is_lock":true,"author":{"name":"翻译组"}}
            ]}}}
            """.trimIndent(),
        )

        assertEquals(2, result.tracks.size)
        assertEquals("bili:1", result.tracks[0].id)
        assertEquals("中文（简体）", result.tracks[0].label)
        assertEquals("https://comment.bilibili.com/1.json", result.tracks[0].url)
        assertEquals("字幕君", result.tracks[0].authorName)
        assertEquals(VideoSubtitleSource.Bilibili, result.tracks[0].source)
        assertEquals("bili:3", result.tracks[1].id)
        assertTrue(result.tracks[1].isLocked)
        // 同一语言同时存在人工与 AI 字幕时只保留人工字幕。
        assertTrue(result.tracks.none { it.isAiGenerated })
        assertEquals(false, result.needLoginSubtitle)
        assertEquals(false, result.allowSubmit)
    }

    @Test
    fun parsesBilibiliSubtitleTracksHandlesLoginRequiredAiOnlyAndStructureChanges() {
        val needLogin = parseBilibiliSubtitleTracks(
            """{"code":0,"data":{"need_login_subtitle":true,"subtitle":{"allow_submit":true,"subtitles":[
                 {"id_str":"9","lan":"ai-zh","lan_doc":"AI 中文","subtitle_url":"https://aisubtitle.hdslb.com/bfs/ai_subtitle/9.json","ai_type":1},
                 {"id_str":"10","lan":"zh-CN","lan_doc":"缺少地址","subtitle_url":""}]}}}""",
        )
        // 只有 AI 字幕时保留该轨道；缺少 subtitle_url 的轨道直接丢弃。
        assertEquals(1, needLogin.tracks.size)
        assertTrue(needLogin.tracks.single().isAiGenerated)
        assertTrue(needLogin.needLoginSubtitle)
        assertTrue(needLogin.allowSubmit)

        assertTrue(parseBilibiliSubtitleTracks("not json").tracks.isEmpty())
        assertTrue(parseBilibiliSubtitleTracks("""{"code":-400,"data":{}}""").tracks.isEmpty())
        assertTrue(parseBilibiliSubtitleTracks("""{"code":0}""").tracks.isEmpty())
    }

    @Test
    fun parsesBilibiliSubtitleDocumentAndAiTranscript() {
        val cues = parseBilibiliSubtitleDocument(
            """{"body":[{"from":0.5,"to":2,"content":"第一句"},{"from":2,"to":2,"content":"零长度"},
                {"from":2,"to":4.25,"content":"<i>第二句</i>"}]}""",
        )
        assertEquals(2, cues.size)
        assertEquals(VideoSubtitleCue(500, 2_000, "第一句"), cues[0])
        assertEquals(VideoSubtitleCue(2_000, 4_250, "第二句"), cues[1])

        val ai = parseBilibiliAiSubtitle(
            """{"code":0,"data":{"model_result":{"subtitle":[
                 {"part_subtitle":[{"start_timestamp":1,"end_timestamp":3,"content":"AI 第一句"},
                 {"start_timestamp":3,"end_timestamp":5,"content":"AI 第二句"}]}]}}}""",
        )
        assertEquals(2, ai.size)
        assertEquals(VideoSubtitleCue(1_000, 3_000, "AI 第一句"), ai[0])
        assertEquals(VideoSubtitleCue(3_000, 5_000, "AI 第二句"), ai[1])

        assertTrue(parseBilibiliAiSubtitle("not json").isEmpty())
        assertTrue(parseBilibiliAiSubtitle("""{"code":-404}""").isEmpty())
        assertTrue(parseBilibiliAiSubtitle("""{"code":0,"data":{}}""").isEmpty())
    }

    @Test
    fun picksPrimaryAndSecondarySubtitleTracks() {
        val tracks = listOf(
            VideoSubtitleTrack(id = "bili:1", label = "中文", language = "zh-CN", source = VideoSubtitleSource.Bilibili),
            VideoSubtitleTrack(id = "bili:2", label = "AI 中文", language = "ai-zh", isAiGenerated = true, source = VideoSubtitleSource.Bilibili),
            VideoSubtitleTrack(id = "bili:3", label = "英语", language = "en-US", source = VideoSubtitleSource.Bilibili),
        )

        assertEquals("bili:1", pickDefaultVideoSubtitleTrack(tracks)?.id)
        // 副字幕默认选不同语言，避免与主字幕重复；AI 中文与主字幕同语言也应跳过。
        assertEquals("bili:3", pickDefaultVideoSecondarySubtitleTrack(tracks, "bili:1")?.id)
        assertEquals("bili:3", pickDefaultVideoSecondarySubtitleTrack(tracks, "bili:2")?.id)
        assertEquals("bili:1", pickDefaultVideoSecondarySubtitleTrack(tracks, "bili:3")?.id)
    }
}
