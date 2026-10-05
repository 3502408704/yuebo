package com.example.local_music_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 有声书角色轨道纯函数：脚本解析（角色/情绪/【键=值】）、段级提示词、多段 WAV 拼接、格式决策。
 * 【2026-10-05 归档】随 TTS 工作台源码一并存档，不参与编译。 */
class TtsAudiobookTest {

    // —— 格式决策：压缩选择全程直通（选什么格式输出什么格式，零转码）；无损选择 WAV 头部顺接 ——

    @Test
    fun track_format_plan_compressed_selection_passes_through_as_mp3() {
        // 目录默认（微软/MiMo 都是 mp3）与各 mp3 档位：片段即所选格式，产物 mp3 直通，不转码
        assertEquals(TtsTrackFormatPlan("mp3", "mp3"), ttsTrackFormatPlan("mp3"))
        assertEquals(TtsTrackFormatPlan("mp3", "mp3"), ttsTrackFormatPlan(""))
        assertEquals(TtsTrackFormatPlan("mp3", "mp3"), ttsTrackFormatPlan(null))
        // 微软档位别名原样透传（该档位仅其原生引擎可解），产物容器归并为 mp3
        assertEquals(TtsTrackFormatPlan("mp3-24k-160k", "mp3"), ttsTrackFormatPlan("mp3-24k-160k"))
    }

    @Test
    fun track_format_plan_lossless_selection_keeps_wav_end_to_end() {
        assertEquals(TtsTrackFormatPlan("wav", "wav"), ttsTrackFormatPlan("wav"))
        assertEquals(TtsTrackFormatPlan("wav", "wav"), ttsTrackFormatPlan("wav-16k"))
        // pcm/pcm16（MiMo 原始线性格式）按无损对待
        assertEquals(TtsTrackFormatPlan("wav", "wav"), ttsTrackFormatPlan("pcm"))
        assertEquals(TtsTrackFormatPlan("wav", "wav"), ttsTrackFormatPlan("pcm16"))
        // 大小写与空白容忍（目录可能下发大写档位）
        assertEquals(TtsTrackFormatPlan("wav", "wav"), ttsTrackFormatPlan("WAV "))
    }

    @Test
    fun parser_extracts_roles_and_defaults_narrator() {
        val segments = parseTtsAudiobookScript(
            """
            旁白：夜色渐深。
            张三：别出声。
            李四：怎么了？
            没有冒号的散句归旁白
            """.trimIndent(),
        )
        assertEquals(listOf("旁白", "张三", "李四", "旁白"), segments.map { it.roleName })
        assertEquals("夜色渐深。", segments[0].text)
        assertEquals("没有冒号的散句归旁白", segments[3].text)
    }

    @Test
    fun parser_reads_paren_emotion_from_role_and_line_end() {
        val segments = parseTtsAudiobookScript("张三（低声）：别出声，有人来了。（紧张）")
        assertEquals(1, segments.size)
        // 角色侧与行尾都标注时取第一个遇到的（角色侧优先），行尾标注被剥离不再朗读
        assertEquals("低声", segments[0].emotion)
        assertEquals("别出声，有人来了。", segments[0].text)
    }

    @Test
    fun parser_reads_bracket_structured_fields_in_role_name() {
        val segments = parseTtsAudiobookScript("张三【情绪=紧张；场景=雨夜；语气=急促；停顿=短】：有人来了")
        assertEquals(1, segments.size)
        assertEquals("张三", segments[0].roleName)
        assertEquals("紧张", segments[0].emotion)
        assertEquals("急促", segments[0].tone)
        assertEquals("雨夜", segments[0].scene)
        assertEquals("短", segments[0].pause)
    }

    @Test
    fun parser_keeps_order_and_skips_blank_lines() {
        val segments = parseTtsAudiobookScript("张三：一。\n\n  \n李四：二。\n旁白：")
        assertEquals(listOf("张三", "李四"), segments.map { it.roleName })
        assertEquals("一。", segments[0].text)
        // 「旁白：」没有台词 → 整行丢弃
    }

    @Test
    fun roles_are_distinct_in_first_seen_order() {
        val segments = parseTtsAudiobookScript("张三：一\n旁白：二\n张三：三\n李四：四")
        assertEquals(listOf("张三", "旁白", "李四"), audiobookRoles(segments))
    }

    @Test
    fun segment_prompt_composes_role_identity_plus_performance() {
        val segment = TtsAudiobookSegment("张三", "有人来了", emotion = "警惕", tone = "急促", scene = "", pause = "")
        val prompt = buildTtsAudiobookSegmentPrompt("张三", "低沉克制", segment)
        // 字段全量在场（含边界声明行）；空字段跳过
        listOf("角色轨道：张三", "角色基调：低沉克制", "情绪：警惕", "语气：急促")
            .forEach { assertTrue(prompt.contains(it)) }
        val minimal = buildTtsAudiobookSegmentPrompt("张三", "", segment.copy(emotion = "", tone = ""))
        assertTrue(minimal.contains("角色轨道：张三"))
        assertFalse(minimal.contains("角色基调"))
    }

    @Test
    fun segment_prompt_declares_control_boundary() {
        val segment = TtsAudiobookSegment("张三", "有人来了", emotion = "警惕", tone = "急促")
        val prompt = buildTtsAudiobookSegmentPrompt("张三", "低沉克制", segment)
        // 指令侧收口：明确声明这是控制信息，不朗读字段名/标签/说明文字
        assertTrue(prompt.contains("只用于把握情绪与节奏，不是朗读内容"))
        assertTrue(prompt.contains("不要朗读字段名、标签或任何说明文字"))
        assertTrue(prompt.contains("情绪：警惕"))
    }

    @Test
    fun script_emotion_removes_conflicting_role_emotion() {
        val segment = TtsAudiobookSegment("张三", "别过来", emotion = "愤怒")
        val prompt = buildTtsAudiobookSegmentPrompt("张三", "情绪：平静；低沉克制", segment)
        assertTrue(prompt.contains("情绪：愤怒"))
        assertFalse(prompt.contains("情绪：平静"))
        assertTrue(prompt.contains("最高优先级"))
        assertEquals("低沉克制", stripTtsEmotionDirectives("情绪：平静；低沉克制"))
    }

    @Test
    fun spoken_text_is_raw_line_text_without_tags_or_emotion() {
        // 2026-09-23 情感泄露修复：朗读正文只传原始台词，音频标签/情绪/语气/场景一律不进 input
        val tagged = TtsAudiobookSegment("张三", "有人来了", emotion = "紧张", tone = "急促",
            scene = "雨夜", pause = "短", tags = "[吸气]")
        assertEquals("有人来了", audiobookSpokenText(tagged))
        assertEquals("有人来了", audiobookSpokenText(
            TtsAudiobookSegment("张三", "有人来了")))
    }

    @Test
    fun wav_concat_rewrites_headers_and_joins_bodies() {
        fun wav(size: Int): ByteArray {
            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            "WAVE".toByteArray().copyInto(header, 8)
            "data".toByteArray().copyInto(header, 36)
            return header + ByteArray(size) { 0x11 }
        }
        val merged = concatTtsWav(listOf(wav(100), wav(200)))
        // RIFF size = 总长-8；data size = 各段 data 之和
        val riffSize = merged[4].toInt() and 0xFF or ((merged[5].toInt() and 0xFF) shl 8) or
            ((merged[6].toInt() and 0xFF) shl 16) or ((merged[7].toInt() and 0xFF) shl 24)
        val dataSize = merged[40].toInt() and 0xFF or ((merged[41].toInt() and 0xFF) shl 8) or
            ((merged[42].toInt() and 0xFF) shl 16) or ((merged[43].toInt() and 0xFF) shl 24)
        assertEquals(44 + 300 - 8, riffSize)
        assertEquals(300, dataSize)
        assertEquals(344, merged.size)
        assertTrue(merged[44] == 0x11.toByte() && merged[343] == 0x11.toByte())
        // 单段原样返回
        val single = wav(50)
        assertTrue(concatTtsWav(listOf(single)).contentEquals(single))
    }

    @Test
    fun chunk_format_detection_covers_wav_and_mp3_bytes() {
        fun wav(size: Int): ByteArray {
            val header = ByteArray(44)
            "RIFF".toByteArray().copyInto(header, 0)
            "WAVE".toByteArray().copyInto(header, 8)
            "data".toByteArray().copyInto(header, 36)
            return header + ByteArray(size) { 0x22 }
        }
        // 格式探测：完整 RIFF/WAVE/data 头才算 WAV；MP3 字节流（ID3 头）不算
        assertTrue(ttsChunkIsWav(wav(10)))
        assertFalse(ttsChunkIsWav(byteArrayOf()))
        val mp3 = "ID3" + ByteArray(50) { 0x33 }
        assertFalse(ttsChunkIsWav(mp3.toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun long_script_line_splits_at_sentence_boundaries_with_safe_limit() {
        val text = "甲".repeat(700) + "。" + "乙".repeat(700)
        val chunks = splitTtsAudiobookText(text, maxChars = 800)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.length <= 800 })
        assertEquals(text, chunks.joinToString(""))
        assertEquals(listOf("短句"), splitTtsAudiobookText("短句", maxChars = 800))
    }

    @Test
    fun default_request_limit_keeps_single_requests_small() {
        // 2026-09-30 起单次合成请求默认拆到 200 字：短请求合成更快、排队更短。
        // 长台词必须在句读边界切碎、拼回无损，且任何一段都不超过默认上限。
        val sentence = "这是第一句话。这是第二句话，中间带逗号。这是第三句话！"
        val text = sentence.repeat(12)
        val chunks = splitTtsAudiobookText(text)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= TTS_ROLE_REQUEST_MAX_CHARS })
        assertEquals(text, chunks.joinToString(""))
        assertEquals(listOf("短句"), splitTtsAudiobookText("短句"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wav_concat_rejects_invalid_header() {
        concatTtsWav(listOf(ByteArray(44), ByteArray(44)))  // 多段才走头校验（单段原样透传）
    }

    @Test(expected = IllegalArgumentException::class)
    fun wav_concat_rejects_empty() {
        concatTtsWav(emptyList())
    }

    // —— 编辑文本：行级角色识别与台词回写 ——

    @Test
    fun line_role_resolves_prefix_bracket_and_paren() {
        assertEquals("张三", ttsScriptLineRole("张三：你好"))
        assertEquals("张三", ttsScriptLineRole("张三（高兴）：你好"))
        assertEquals("张三", ttsScriptLineRole("张三【情绪=高兴】：你好"))
        assertEquals("旁白", ttsScriptLineRole("没有冒号的散句"))
        assertEquals(null, ttsScriptLineRole("张三："))  // 纯角色头无台词
        assertEquals(null, ttsScriptLineRole("   "))
    }

    @Test
    fun replace_role_lines_updates_target_and_preserves_others() {
        val script = "张三（平静）：第一句\n旁白：旁白行\n李四：李四的台词\n张三：第二句"
        val updated = replaceRoleLinesInScript(script, "张三", listOf("新的第一句", "新的第二句"))
        // 交错脚本原位替换：张三两处台词各自更新，位置不折叠、行数不丢（旧行为会把第二处删掉＝改台词）
        assertEquals(
            listOf("张三：新的第一句", "旁白：旁白行", "李四：李四的台词", "张三：新的第二句"),
            updated.lines(),
        )
        // 回写后解析：角色集合与其余角色台词原样保留，张三两句都在
        val parsed = parseTtsAudiobookScript(updated)
        assertEquals(listOf("张三", "旁白", "李四"), audiobookRoles(parsed))
        assertEquals("李四的台词", parsed.last { it.roleName == "李四" }.text)
        assertEquals(listOf("新的第一句", "新的第二句"), parsed.filter { it.roleName == "张三" }.map { it.text })
    }

    @Test
    fun replace_role_lines_narrator_bare_and_missing_role_untouched() {
        // 旁白行不加前缀；脚本里没有该角色时原样返回
        assertEquals("旁白新行\n张三：台词", replaceRoleLinesInScript("第一段\n张三：台词", "旁白", listOf("旁白新行")))
        assertEquals("保持不变", replaceRoleLinesInScript("保持不变", "王五", listOf("x")))
    }

    @Test
    fun role_track_text_joins_segment_lines() {
        val segments = parseTtsAudiobookScript("张三：一\n旁白：二\n张三：三")
        assertEquals("一\n三", roleTrackText(segments, "张三"))
        assertEquals("", roleTrackText(segments, "李四"))
    }

    // —— 角色配置：参数合并 / 音色摘要 / 模型解析 ——

    private fun presetForConfig() = TtsModelInfo(
        id = "p", label = "P", engine = "E1", mode = "preset", price = "",
        voices = listOf(TtsVoice("v1", "冰糖", "zh", "女", "")), maxChars = 100, paramsSchema = emptyList(),
        supportsInstructions = false, supportsVoiceDesign = false,
        supportsReferenceAudio = false, supportsSpeed = false, supportsTemperature = false, supportsStyle = false,
    )

    @Test
    fun role_params_merge_defaults_and_keep_custom_values() {
        val model = TtsModelInfo(
            id = "m", label = "m", engine = "e", mode = "preset", price = "", voices = emptyList(), maxChars = 100,
            paramsSchema = listOf(
                TtsParamSpec("temperature", "T", "slider", 0f, 1f, 0.05f, "0.6"),
                TtsParamSpec("speed", "S", "slider", 0.5f, 2f, 0.1f, "1.0"),
            ),
            supportsInstructions = false, supportsVoiceDesign = false,
            supportsReferenceAudio = false, supportsSpeed = false, supportsTemperature = false, supportsStyle = false,
        )
        val merged = roleParamsFor(model, mapOf("temperature" to "0.9", "speed" to ""))
        assertEquals("0.9", merged["temperature"])  // 自定义值保留
        assertEquals("1.0", merged["speed"])        // 空白值回 schema 默认
        assertEquals(2, merged.size)
        assertTrue(roleParamsFor(null, mapOf("x" to "1")).isEmpty())
    }

    @Test
    fun role_voice_summary_and_model_resolution() {
        val preset = presetForConfig()
        val clone = preset.copy(id = "c", mode = "clone", voices = emptyList())
        assertEquals("预置·冰糖", ttsRoleVoiceSummary(TtsRoleConfig(mode = "preset", modelId = "p", voiceId = "v1"), listOf(preset), ""))
        assertEquals("克隆·a.wav", ttsRoleVoiceSummary(TtsRoleConfig(mode = "clone"), listOf(clone), "a.wav"))
        assertEquals("克隆·未选参考音频", ttsRoleVoiceSummary(TtsRoleConfig(mode = "clone"), listOf(clone), ""))
        assertEquals("设计·描述音色", ttsRoleVoiceSummary(TtsRoleConfig(mode = "design"), listOf(preset), ""))
        // 模型解析：按配置优先，缺失回退该来源第一个；该来源没有引擎返回 null
        assertEquals("c", ttsRoleModel(TtsRoleConfig(mode = "clone", modelId = "c"), listOf(preset, clone))?.id)
        assertEquals("c", ttsRoleModel(TtsRoleConfig(mode = "clone", modelId = "missing"), listOf(preset, clone))?.id)
        assertEquals(null, ttsRoleModel(TtsRoleConfig(mode = "design"), listOf(preset, clone)))
    }
}
