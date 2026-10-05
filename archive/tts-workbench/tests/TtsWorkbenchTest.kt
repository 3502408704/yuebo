package com.example.local_music_player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TTS 工作台纯函数：目录解码（含引擎/模式/价格位/动态参数 schema）、聚合请求构造、语言分组、历史序列化。
 * 【2026-10-05 归档】随 TTS 工作台源码一并存档，不参与编译。 */
class TtsWorkbenchTest {

    private fun presetModel(
        id: String = "mimo-v2.5-tts",
        engine: String = "MiMo",
        voices: List<TtsVoice> = listOf(
            TtsVoice("冰糖", "冰糖", "中文", "女", "你好。"),
            TtsVoice("Mia", "Mia", "英文", "女", "Hello."),
        ),
        schema: List<TtsParamSpec> = listOf(
            TtsParamSpec("temperature", "Temperature", "slider", 0f, 1f, 0.05f, "0.6"),
            TtsParamSpec("seed", "Seed", "text", 0f, 0f, 1f, ""),
        ),
    ) = TtsModelInfo(
        id = id, label = id, engine = engine, mode = "preset", price = "",
        voices = voices, maxChars = 10000, paramsSchema = schema,
        supportsInstructions = true, supportsVoiceDesign = false,
        supportsReferenceAudio = false, supportsSpeed = false, supportsTemperature = true, supportsStyle = false,
    )

    private fun msModel() = TtsModelInfo(
        id = "msedge-tts", label = "微软自然语音", engine = "微软", mode = "preset", price = "",
        voices = listOf(TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN", "Female", "")),
        maxChars = 10000, paramsSchema = listOf(TtsParamSpec("speed", "语速", "slider", 0.5f, 2f, 0.1f, "1.0")),
        supportsInstructions = false, supportsVoiceDesign = false,
        supportsReferenceAudio = false, supportsSpeed = true, supportsTemperature = false, supportsStyle = false,
    )

    private fun designModel() = TtsModelInfo(
        id = "mimo-v2.5-tts-voicedesign", label = "MiMo 音色设计", engine = "MiMo", mode = "design", price = "",
        voices = emptyList(), maxChars = 10000, paramsSchema = emptyList(),
        supportsInstructions = true, supportsVoiceDesign = true,
        supportsReferenceAudio = false, supportsSpeed = false, supportsTemperature = true, supportsStyle = false,
    )

    private fun cloneModel() = TtsModelInfo(
        id = "mimo-v2.5-tts-voiceclone", label = "MiMo 声音克隆", engine = "MiMo", mode = "clone", price = "",
        voices = emptyList(), maxChars = 10000, paramsSchema = emptyList(),
        supportsInstructions = true, supportsVoiceDesign = false,
        supportsReferenceAudio = true, supportsSpeed = false, supportsTemperature = true, supportsStyle = false,
    )

    // —— 目录解码 ——

    @Test
    fun decodeTtsModels_parses_engine_mode_price_and_params_schema() {
        val payload = JSONObject("""
            {"models": [
              {"id": "mimo-v2.5-tts", "label": "MiMo 预置音色", "engine": "MiMo", "mode": "preset",
               "price": "限时免费", "max_chars": 8000,
               "voices": [{"id": "冰糖", "label": "冰糖", "lang": "中文", "gender": "女", "sample": "你好。"}],
               "params_schema": [
                 {"name": "temperature", "label": "Temperature", "type": "slider",
                  "min": 0, "max": 1, "step": 0.05, "default": 0.6},
                 {"name": "seed", "label": "Seed", "type": "text", "default": ""}],
               "params": {"instructions": true, "temperature": true}},
              {"id": "msedge-tts", "label": "微软自然语音", "engine": "微软", "mode": "preset",
               "voices": [{"id": "zh-CN-XiaoxiaoNeural"}],
               "params_schema": [{"name": "speed", "type": "slider", "min": 0.5, "max": 2, "step": 0.1, "default": 1}],
               "params": {"speed": true}}
            ]}
        """.trimIndent())
        val models = decodeTtsModels(payload)
        assertEquals(2, models.size)
        val mimo = models.first()
        assertEquals("MiMo", mimo.engine)
        assertEquals("preset", mimo.mode)
        assertEquals("限时免费", mimo.price)
        assertEquals(8000, mimo.maxChars)
        assertEquals(2, mimo.paramsSchema.size)
        assertEquals(0.6f, mimo.paramsSchema.first().default.toFloatOrNull()!!, 1e-6f)
        val ms = models.last()
        assertEquals("微软", ms.engine)
        assertEquals(1, ms.paramsSchema.size)
        assertTrue(ms.supportsSpeed && !ms.supportsTemperature)
    }

    @Test
    fun decodeTtsModels_missing_mode_defaults_to_preset_and_drops_blank_entries() {
        val payload = JSONObject("""
            {"models": [
              {"id": null},
              {"id": "msedge-tts", "voices": [{"id": null}, {"id": "zh-CN-XiaoyiNeural"}]}
            ]}
        """.trimIndent())
        val models = decodeTtsModels(payload)
        assertEquals(1, models.size)
        assertEquals("preset", models.single().mode)  // 缺 mode 视作预置（向后兼容）
        assertEquals(1, models.single().voices.size)
    }

    @Test
    fun decodeTools_parses_labels_without_usage() {
        val payload = JSONObject("""
            {"tools": [
              {"id": "tts", "label": "AI TTS 工作台", "description": "语音合成与有声书创作",
               "result_type": "audio", "usage": {"limit": 50, "remaining": 47},
               "params": [
                 {"name": "mode", "type": "select", "label": "合成模式", "required": true,
                  "default": "preset",
                  "options": [{"value": "preset", "label": "预置音色"}]},
                 {"name": "voice", "type": "select", "label": "音色",
                  "visible_when": {"field": "mode", "value": "preset"},
                  "options": [{"value": "冰糖", "label": "冰糖"}]},
                 {"name": "image", "type": "image", "label": "图片", "required": false}
               ]},
              {"id": ""}
            ]}
        """.trimIndent())
        val tools = decodeTools(payload)
        assertEquals(1, tools.size)
        val tool = tools.single()
        assertEquals("tts", tool.id)
        assertEquals("AI TTS 工作台", tool.label)
        assertEquals("audio", tool.resultType)
        assertEquals(3, tool.params.size)
        val mode = tool.params[0]
        assertEquals("mode", mode.name)
        assertEquals("select", mode.type)
        assertEquals(true, mode.required)
        assertEquals("preset", mode.default)
        assertEquals(listOf("preset"), mode.options.map { it.value })
        assertEquals("预置音色", mode.options.single().label)
        // visible_when 解析（云工具页按其隐藏字段）
        assertEquals("mode", tool.params[1].visibleWhenField)
        assertEquals("preset", tool.params[1].visibleWhenValue)
        val image = tool.params[2]
        assertEquals("image", image.type)
        assertEquals(false, image.required)
    }

    @Test
    fun ttsModeOptions_derived_from_catalog_and_dynamic() {
        // 目录里有什么模式就列什么；没有引擎的来源不出现（不写死三选）
        val models = listOf(
            presetModel(),                       // preset
            designModel(),                       // design
            cloneModel(),                        // clone
        )
        val all = ttsModeOptions(models)
        assertEquals(listOf("preset", "design", "clone"), all.map { it.mode })
        assertEquals("预置音色", all[0].label)
        assertEquals("音色设计", all[1].label)
        assertEquals("声音克隆", all[2].label)
        // 微软只有预置：来源下拉只剩一项（用户核心诉求——并非每个引擎都有全部能力）
        val presetOnly = ttsModeOptions(listOf(msModel()))
        assertEquals(listOf("preset"), presetOnly.map { it.mode })
        // 未知模式按原名展示（服务端新增模式客户端零改动）
        val exotic = decodeTtsModels(JSONObject(
            "{\"models\":[{\"id\":\"x\",\"mode\":\"vox\",\"voices\":[]}]}"))
        assertEquals(listOf("vox"), ttsModeOptions(exotic).map { it.mode })
        assertEquals("vox", ttsModeOptions(exotic).single().label)
        assertEquals(emptyList<TtsModeOption>(), ttsModeOptions(emptyList()))
    }

    // —— 聚合端点请求构造（参数值来自动态 schema 面板） ——

    @Test
    fun speechRequest_maps_known_param_names_and_respects_capabilities() {
        val body = buildTtsSpeechRequest(
            presetModel(), text = "你好月播", voiceId = "冰糖", instructions = "角色：张三。",
            voiceDesign = "", referenceDataUri = null,
            values = mapOf("temperature" to "0.55", "top_p" to "0.9", "seed" to "7", "speed" to "1.5"),
        )
        assertEquals("mimo-v2.5-tts", body.getString("model"))
        assertEquals("mp3", body.getString("response_format"))
        assertEquals("冰糖", body.getString("voice"))
        assertEquals("角色：张三。", body.getString("instructions"))
        assertEquals(0.55, body.getDouble("temperature"), 1e-6)
        assertEquals(0.9, body.getDouble("top_p"), 1e-6)
        assertEquals(7, body.getInt("seed"))
        assertFalse(body.has("speed"))  // 引擎不支持的能力不进请求
    }

    @Test
    fun speechRequest_blank_seed_and_ms_speed_only() {
        val ms = msModel()
        val body = buildTtsSpeechRequest(
            ms, "测试", "zh-CN-XiaoxiaoNeural", instructions = "角色：旁白。", voiceDesign = "",
            referenceDataUri = null, values = mapOf("speed" to "1.5"),
        )
        assertEquals(1.5, body.getDouble("speed"), 1e-6)
        assertFalse(body.has("instructions") || body.has("temperature") || body.has("seed"))
        // speed 值缺失/非法 → 不下发（走服务端缺省）
        val noSpeed = buildTtsSpeechRequest(
            ms, "测试", "zh-CN-XiaoxiaoNeural", "", "", null, values = mapOf("speed" to ""),
        )
        assertFalse(noSpeed.has("speed"))
    }

    @Test
    fun speechRequest_audiobook_uses_wav_and_clone_carries_reference() {
        val clone = cloneModel()
        val body = buildTtsSpeechRequest(
            clone, "台词", voiceId = null, instructions = "角色轨道：张三", voiceDesign = "",
            referenceDataUri = "data:audio/wav;base64,QUJD",
            values = emptyMap(), responseFormat = "wav",
        )
        assertEquals("wav", body.getString("response_format"))
        assertEquals("data:audio/wav;base64,QUJD", body.getString("reference_audio"))
        assertFalse(body.has("voice") || body.has("voice_design"))
    }

    // —— 音色按语言分组 ——

    @Test
    fun voice_language_labels_cover_common_locales() {
        assertEquals("中文（普通话）", voiceLanguageLabel("zh-CN"))
        assertEquals("中文（普通话）", voiceLanguageLabel("中文"))
        assertEquals("粤语", voiceLanguageLabel("zh-HK"))
        assertEquals("中文（台湾）", voiceLanguageLabel("zh-TW"))
        assertEquals("英语", voiceLanguageLabel("en-US"))
        assertEquals("丹麦语", voiceLanguageLabel("da-DK"))
        assertEquals("中英双语", voiceLanguageLabel("中/英"))
        assertEquals("未标注语言", voiceLanguageLabel(""))
        assertEquals("XX", voiceLanguageLabel("xx-yy"))  // 未知语言按语系主码分组
    }

    @Test
    fun voice_grouping_sorts_chinese_first_and_sorts_within_group() {
        val groups = groupVoicesByLanguage(listOf(
            TtsVoice("en-US-GuyNeural", "Guy", "en-US", "Male", ""),
            TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN", "Female", ""),
            TtsVoice("zh-CN-XiaoyiNeural", "晓伊", "zh-CN", "Female", ""),
            TtsVoice("ja-JP-NanamiNeural", "Nanami", "ja-JP", "Female", ""),
        ))
        assertEquals(listOf("中文（普通话）", "英语", "日语"), groups.map { it.label })
        assertEquals(listOf("晓伊", "晓晓"), groups.first().voices.map { it.label })  // 组内按名称码点稳定排序
    }

    // —— 历史索引序列化 ——

    @Test
    fun history_roundtrip_and_malformed_tolerance() {
        val entries = listOf(
            TtsHistoryEntry("id1", "第一段", "冰糖", "MiMo 预置音色", "tts-1.mp3", 1024L, 1000L),
            TtsHistoryEntry("id2", "有声书·张三", "张三", "有声书·MiMo", "tts-2.wav", 2048L, 2000L),
        )
        assertEquals(entries, decodeTtsHistory(encodeTtsHistory(entries)))
        assertTrue(decodeTtsHistory("not-json").isEmpty())
        assertTrue(decodeTtsHistory("[{\"file\":\"\"}]").isEmpty())
    }

    // —— 格式解析 ——

    @Test
    fun format_resolution_prefers_header_and_falls_back_to_content_type() {
        assertEquals("mp3", ttsFormatOrMp3("mp3", "audio/mpeg"))
        assertEquals("wav", ttsFormatOrMp3("wav", null))
        assertEquals("mp3", ttsFormatOrMp3(null, "audio/mpeg"))
        assertEquals("mp3", ttsFormatOrMp3("", "application/json"))
        assertEquals("mp3", ttsFormatOrMp3(null, null))
    }

    // —— 工作台子页窗口标题（读屏 paneTitle，MusicApp screenPaneTitle 调用） ——

    @Test
    fun workbench_pane_title_per_page() {
        assertEquals("AI TTS 工作台", ttsWorkbenchPaneTitle(TTS_PAGE_MAIN, "", ""))
        assertEquals("角色语音·张三", ttsWorkbenchPaneTitle(TTS_PAGE_VOICE, "张三", ""))
        assertEquals("角色语音", ttsWorkbenchPaneTitle(TTS_PAGE_VOICE, "", ""))
        assertEquals("选择音色", ttsWorkbenchPaneTitle(TTS_PAGE_VOICES, "张三", ""))
        assertEquals("音色·中文（普通话）", ttsWorkbenchPaneTitle(TTS_PAGE_VOICES, "张三", "中文（普通话）"))
        assertEquals("合成参数", ttsWorkbenchPaneTitle(TTS_PAGE_PARAMS, "张三", ""))
        assertEquals("AI TTS 工作台", ttsWorkbenchPaneTitle("unknown", "", ""))
    }
    // —— 情感通道：微软 style 仅对支持的引擎下发；情绪词映射 ——

    @Test
    fun speechRequest_style_only_for_capable_engines() {
        val msStyle = msModel().copy(supportsStyle = true)
        val body = buildTtsSpeechRequest(
            msStyle, "台词", "zh-CN-XiaoxiaoNeural", instructions = "", voiceDesign = "",
            referenceDataUri = null, values = mapOf("speed" to "1.2", "style_degree" to "1.5"),
            style = "cheerful",
        )
        assertEquals("cheerful", body.getString("style"))
        assertEquals(1.5, body.getDouble("style_degree"), 1e-6)
        // 引擎不支持 style 时不下发（MiMo 的情感走 instructions 通道）
        val plain = buildTtsSpeechRequest(
            msModel(), "台词", "zh-CN-XiaoxiaoNeural", "", "", null,
            values = emptyMap(), style = "cheerful",
        )
        assertFalse(plain.has("style") || plain.has("style_degree"))
        // 情感词为空不下发（走音色默认演绎）
        val blank = buildTtsSpeechRequest(
            msStyle, "台词", "zh-CN-XiaoxiaoNeural", "", "", null,
            values = emptyMap(), style = " ",
        )
        assertFalse(blank.has("style"))
    }

    @Test
    fun edge_style_maps_common_emotions() {
        assertEquals("cheerful", ttsEdgeStyle("欢快"))
        assertEquals("cheerful", ttsEdgeStyle("兴奋"))
        assertEquals("", ttsEdgeStyle("不开心"))
        assertEquals("fearful", ttsEdgeStyle("不开心但很紧张"))
        assertEquals("sad", ttsEdgeStyle("悲伤"))
        assertEquals("angry", ttsEdgeStyle("愤怒"))
        assertEquals("gentle", ttsEdgeStyle("温柔"))
        assertEquals("fearful", ttsEdgeStyle("紧张"))
        assertEquals("serious", ttsEdgeStyle("严肃"))
        assertEquals("", ttsEdgeStyle("疑惑"))
        assertEquals("", ttsEdgeStyle("惊讶"))  // 微软无 surprised 风格，不猜——未映射词回空
        assertEquals("", ttsEdgeStyle(""))
    }

    @Test
    fun edge_style_covers_full_style_vocabulary() {
        // 服务端目录 12 个 style 全部可映射（52 批扩展：friendly/whisper/newscast/lyrical）
        assertEquals("friendly", ttsEdgeStyle("友好"))
        assertEquals("friendly", ttsEdgeStyle("友善"))
        assertEquals("whisper", ttsEdgeStyle("低语"))  // Azure 真实 style 名（2026-09-28 实测演示通道可用）
        assertEquals("newscast", ttsEdgeStyle("播报"))
        assertEquals("lyrical", ttsEdgeStyle("抒情"))
    }

    // —— 情感标签的引擎感知：微软（style 通道）下发可映射词表，MiMo（instructions 通道）自由词 ——
    // 请求构造断言见 TtsTextAiTest.request_carries_emotion_vocab_for_style_engines

    @Test
    fun emotion_vocab_follows_engine_channel() {
        val ms = msModel().copy(supportsStyle = true)
        // 风格映射引擎：词表=所选音色的真实风格清单（映射为中文标签）
        val voiced = ms.copy(voices = listOf(
            TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN", "Female", "", styles = listOf("cheerful", "sad", "whisper")),
        ))
        val vocab = ttsEmotionVocabFor(ms, voiced.voices.first())
        assertTrue(vocab.isNotBlank())
        assertTrue(vocab.split("、").all { ttsEdgeStyle(it).isNotBlank() })  // 词表每个词都可映射
        // 音色没有风格清单：没有可下发的 style，返回空串（调用方停用 AI 情感入口）——
        // 不再回退通用词表（通用词会产生音色不支持的 style，2026-09-29 修复）
        assertEquals("", ttsEmotionVocabFor(ms))
        assertEquals("", ttsEmotionVocabFor(ms, voiced.voices.first().copy(styles = emptyList())))
        assertEquals("", ttsEmotionVocabFor(presetModel()))  // MiMo：自由情绪词
        assertEquals("", ttsEmotionVocabFor(null))
    }

    // —— 输出格式：目录 formats 解码 + 角色默认格式（default_format 优先，回退 formats 首位） ——

    @Test
    fun catalog_formats_decoded_and_default_format_follows_engine() {
        fun modelJson(id: String, engine: String, formats: String): JSONObject =
            JSONObject().put("id", id).put("engine", engine).put("mode", "preset")
                .put("voices", org.json.JSONArray()).put("formats", org.json.JSONArray(listOf(*formats.split(",").toTypedArray())))
                .put("params", JSONObject())
        val payload = JSONObject().put("models", org.json.JSONArray(listOf(
            modelJson("msedge-tts", "微软", "wav,mp3"),
            modelJson("mimo-v2.5-tts", "MiMo", "wav"),
        )))
        val models = decodeTtsModels(payload).associateBy { it.id }
        assertEquals(listOf("wav", "mp3"), models["msedge-tts"]?.formats)
        assertEquals(listOf("wav"), models["mimo-v2.5-tts"]?.formats)
        assertEquals("wav", ttsDefaultFormatFor(models["msedge-tts"]))
        assertEquals("wav", ttsDefaultFormatFor(models["mimo-v2.5-tts"]))
        assertEquals("wav", ttsDefaultFormatFor(null))  // 目录未加载兜底
        assertEquals("mp3", ttsDefaultFormatFor(presetModel().copy(formats = listOf("mp3", "wav"))))
    }

    @Test
    fun default_format_and_supports_direct_decoded_from_catalog() {
        // 2026-09-23 能力字段：default_format 决定网络传输默认（微软/MiMo=mp3、design=wav），
        // supports_direct=false（微软）客户端不再申请直连票据；旧目录缺省 supports_direct=true 兼容
        fun modelJson(id: String, extra: JSONObject.() -> Unit = {}): JSONObject =
            JSONObject().put("id", id).put("engine", "e").put("mode", "preset")
                .put("voices", org.json.JSONArray()).put("params", JSONObject())
                .put("formats", org.json.JSONArray(listOf("wav", "mp3"))).apply(extra)
        val models = decodeTtsModels(JSONObject().put("models", org.json.JSONArray(listOf(
            modelJson("msedge-tts") {
                put("default_format", "mp3")
                put("supports_direct", false)
            },
            modelJson("mimo-v2.5-tts") { put("default_format", "mp3") },
            modelJson("mimo-old"),  // 旧目录：无能力字段
        )))).associateBy { it.id }
        assertEquals("mp3", models["msedge-tts"]?.defaultFormat)
        assertEquals(false, models["msedge-tts"]?.supportsDirect)
        assertEquals("mp3", models["mimo-v2.5-tts"]?.defaultFormat)
        assertEquals(true, models["mimo-v2.5-tts"]?.supportsDirect)
        assertEquals(true, models["mimo-old"]?.supportsDirect)  // 缺省兼容
        assertEquals("mp3", ttsDefaultFormatFor(models["msedge-tts"]))  // default_format 优先于 formats 首位
        assertEquals("mp3", ttsDefaultFormatFor(models["mimo-old"]!!.copy(defaultFormat = "mp3")))
    }

    // —— 合成路线决策：工作台的 MiMo/微软轨道统一走服务器转发 ——

    private fun mimoModel() = presetModel().copy(id = "mimo-v2.5-tts", supportsDirect = true)

    @Test
    fun route_decision_sends_microsoft_and_disabled_direct_to_relay() {
        assertEquals("relay", ttsRouteFor(mimoModel(), directDisabled = false))  // MiMo 直连不作为默认工作路径
        assertEquals("relay", ttsRouteFor(mimoModel(), directDisabled = true))
        val ms = msModel().copy(supportsStyle = true, supportsDirect = false)
        assertEquals("relay", ttsRouteFor(ms, directDisabled = false))  // 微软：目录下发不支持直连
        assertEquals("relay", ttsRouteFor(ms.copy(supportsDirect = true), directDisabled = false))  // msedge 系兜底
        assertEquals("relay", ttsRouteFor(null, directDisabled = false))
    }

    // —— 断点续合缓存：按片段索引存放，乱序完成按索引写入，容量随分段数对齐 ——

    @Test
    fun role_chunks_store_by_index_and_resize() {
        val store = TtsRoleChunks(configHash = 1, textsHash = 2, chunkCount = 4)
        assertEquals(4, store.chunks.size)
        assertEquals(0, store.completedCount)
        assertFalse(store.isComplete)
        // 乱序完成：先写 index 2、再写 index 0——按索引存放，读取顺序仍按脚本
        store.chunks[2] = byteArrayOf(0x03)
        store.chunks[0] = byteArrayOf(0x01)
        assertEquals(2, store.completedCount)
        assertEquals(byteArrayOf(0x01).toList(), store.chunks[0]!!.toList())
        assertFalse(store.isComplete)
        // 全部就位
        store.chunks[1] = byteArrayOf(0x02)
        store.chunks[3] = byteArrayOf(0x04)
        assertTrue(store.isComplete)
        // 台词分段数变化：容量对齐，已完成部分保留
        store.resize(6)
        assertEquals(6, store.chunks.size)
        assertEquals(4, store.completedCount)
        store.resize(3)
        assertEquals(3, store.chunks.size)
        assertEquals(3, store.completedCount)
    }

    // —— 情感参数：脚本情绪优先，AI 标签只填补空白 ——

    @Test
    fun segment_emotion_prefers_aligned_ai_tags() {
        val tags = listOf("平静", "温柔", "")
        assertEquals("紧张", ttsSegmentEmotion(tags, 3, 0, "紧张"))
        assertEquals("温柔", ttsSegmentEmotion(tags, 3, 1, ""))
        // 脚本情绪仍然保留
        assertEquals("严肃", ttsSegmentEmotion(tags, 3, 2, "严肃"))
        // 脚本为空时才采用对齐的 AI 标签
        assertEquals("平静", ttsSegmentEmotion(tags, 3, 0, ""))
        // 标签行数与分段数不一致（脚本改过）→ 整组失效，脚本仍保留
        assertEquals("紧张", ttsSegmentEmotion(tags, 4, 0, "紧张"))
        assertEquals("平静", ttsSegmentEmotion(null, 3, 0, "平静"))
    }

    // —— 合成重试：429/5xx 网关类可自动重试（429 优先服务端 Retry-After），配额用尽不重试 ——

    @Test
    fun synth_retry_only_for_rate_limit_and_upstream_errors() {
        // 四次重试机会（attempt 0..3），退避递增；429 优先服务端 Retry-After，封顶 30s
        assertEquals(4000L, ttsSynthRetryDelayMs(429, 0, 0))
        assertEquals(8000L, ttsSynthRetryDelayMs(429, 8, 1))             // Retry-After 优先
        assertEquals(30000L, ttsSynthRetryDelayMs(429, 60, 1))           // 封顶 30s
        assertEquals(3000L, ttsSynthRetryDelayMs(503, 0, 0))
        assertEquals(3000L, ttsSynthRetryDelayMs(0, 0, 0))               // IOException（传输层）
        assertEquals(8000L, ttsSynthRetryDelayMs(503, 0, 1))
        assertEquals(15000L, ttsSynthRetryDelayMs(503, 0, 2))
        assertEquals(30000L, ttsSynthRetryDelayMs(0, 0, 3))
        assertEquals(3000L, ttsSynthRetryDelayMs(502, 0, 0, "tts_empty_audio"))
        // 502（转发器上游抖动窗口）/504 必须可重试，否则窗口内整段直接报废（2026-09-29 生产实证）
        assertEquals(3000L, ttsSynthRetryDelayMs(502, 0, 0, "tts_upstream_error"))
        assertEquals(8000L, ttsSynthRetryDelayMs(502, 0, 1))
        assertEquals(3000L, ttsSynthRetryDelayMs(504, 0, 0))
        assertEquals(null, ttsSynthRetryDelayMs(503, 0, 4))              // 次数用尽
        assertEquals(null, ttsSynthRetryDelayMs(0, 0, 4))
        assertEquals(null, ttsSynthRetryDelayMs(403, 30, 0))             // 配额用尽：提示而非静默重试
        assertEquals(null, ttsSynthRetryDelayMs(401, 0, 0))
    }

    @Test
    fun default_voice_prefers_chinese_when_switching_engines() {
        val ms = TtsModelInfo(
            id = "msedge-tts", label = "微软", engine = "微软", mode = "preset", price = "",
            voices = listOf(
                TtsVoice("Adri", "Adri", "en-US", "Female", ""),
                TtsVoice("zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN", "Female", ""),
                TtsVoice("ja-Nanami", "Nanami", "ja-JP", "Female", ""),
            ),
            maxChars = 10000, paramsSchema = emptyList(),
            supportsInstructions = false, supportsVoiceDesign = false, supportsReferenceAudio = false,
            supportsTemperature = false, supportsSpeed = true, supportsStyle = true,
        )
        assertEquals("zh-CN-XiaoxiaoNeural", ttsDefaultVoiceId(ms))  // 英文在前也优先中文
        assertEquals("冰糖", ttsDefaultVoiceId(presetModel()))       // 中文音色直接取首个
        assertEquals("", ttsDefaultVoiceId(null))
        assertEquals("", ttsDefaultVoiceId(ms.copy(voices = emptyList())))
    }

    // —— 合成任务轮询（2026-09-30）：409/丢包后按任务 ID 取结果，不盲目重发 ——

    @Test
    fun task_wait_decode_replays_audio_and_maps_states() {
        // completed → 音频回放（Content-Type 即格式事实源，wav 保持 wav 不误判 mp3）
        val done = decodeTtsTaskWait("audio/wav", ByteArray(44))
        assertTrue(done is TtsTaskWait.Done)
        assertEquals("wav", (done as TtsTaskWait.Done).audio.format)
        val mp3 = decodeTtsTaskWait("audio/mpeg; charset=binary", ByteArray(10))
        assertEquals("mp3", (mp3 as TtsTaskWait.Done).audio.format)
        // 空音频体是软失败：按可重发处理，不让空字节混进轨道
        assertTrue(decodeTtsTaskWait("audio/wav", ByteArray(0)) is TtsTaskWait.Resend)
        // running → null（继续轮询）；failed/expired/unknown → 同键重发安全
        assertEquals(null, decodeTtsTaskWait("application/json", """{"state":"running"}""".toByteArray()))
        assertTrue(decodeTtsTaskWait("application/json", """{"state":"failed"}""".toByteArray()) is TtsTaskWait.Resend)
        assertTrue(decodeTtsTaskWait("application/json", """{"state":"expired"}""".toByteArray()) is TtsTaskWait.Resend)
        assertTrue(decodeTtsTaskWait("application/json", """{"state":"unknown"}""".toByteArray()) is TtsTaskWait.Resend)
        // 解析不了的响应（网关 HTML/空体）按 unknown 处理：重发最多 409/重执行一次，安全
        assertTrue(decodeTtsTaskWait("text/html", "<html/>".toByteArray()) is TtsTaskWait.Resend)
        assertTrue(decodeTtsTaskWait("", ByteArray(0)) is TtsTaskWait.Resend)
    }

    @Test
    fun task_poll_constants_keep_polling_light() {
        // 轮询是轻请求：步长必须明显小于 409 等待预算，断网检查步长小于轮询步长
        assertTrue(TTS_TASK_POLL_INTERVAL_MS in 1..IDEMPOTENCY_CONFLICT_BUDGET_MS)
        assertTrue(TTS_TASK_OFFLINE_POLL_MS in 1 until TTS_TASK_POLL_INTERVAL_MS)
    }
}
