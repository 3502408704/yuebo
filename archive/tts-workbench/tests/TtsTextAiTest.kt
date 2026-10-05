package com.example.local_music_player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI 文本赋能（/v1/ai/text）纯函数：请求构造、响应解析、AI 字段契约与客户端面板字段一致。
 * 【2026-10-05 归档】随 TTS 工作台源码一并存档，不参与编译。 */
class TtsTextAiTest {

    @Test
    fun request_carries_mode_and_text() {
        val body = buildTtsAiTextRequest(TTS_AI_MODE_SCRIPT, "月光洒在湖面上。")
        assertEquals("script", body.getString("mode"))
        assertEquals("月光洒在湖面上。", body.getString("text"))
        assertEquals("direct", TTS_AI_MODE_DIRECT)
        assertEquals("voice", TTS_AI_MODE_VOICE)
    }

    @Test
    fun request_carries_emotion_vocab_for_style_engines() {
        // emotion 模式 + 风格映射引擎词表：随请求下发服务端（AI 只从清单选词）
        val withVocab = buildTtsAiTextRequest(TTS_AI_MODE_EMOTION, "小雨：晚安。", "高兴、悲伤")
        assertEquals("高兴、悲伤", withVocab.getString("emotion_vocab"))
        // 非 emotion 模式 / 空词表不下发字段（MiMo 自由情绪词路径零变化）
        assertFalse(buildTtsAiTextRequest(TTS_AI_MODE_EMOTION, "文本").has("emotion_vocab"))
        assertFalse(buildTtsAiTextRequest(TTS_AI_MODE_EMOTION, "文本", "  ").has("emotion_vocab"))
        assertFalse(buildTtsAiTextRequest(TTS_AI_MODE_SCRIPT, "文本", "高兴、悲伤").has("emotion_vocab"))
    }

    @Test
    fun textResult_decodes_script_and_fallback() {
        val ok = decodeTtsAiTextResult(
            JSONObject().put("mode", "script").put("text", "旁白：你好。").put("fallback", false))
        assertEquals("旁白：你好。", ok?.text)
        assertEquals(false, ok?.fallback)
        val fallback = decodeTtsAiTextResult(
            JSONObject().put("text", "原文").put("fallback", true))
        assertEquals("原文", fallback?.text)
        assertEquals(true, fallback?.fallback)
        // 空/缺失文本视为无效结果（调用方提示失败）
        assertNull(decodeTtsAiTextResult(JSONObject().put("text", "")))
        assertNull(decodeTtsAiTextResult(JSONObject()))
    }

    @Test
    fun paramsResult_decodes_director_with_speed() {
        val params = JSONObject()
            .put("role", "旁白")
            .put("emotion", "平静")
            .put("taboo", "")
            .put("suggested_speed", 0.9)
        val result = decodeTtsAiParamsResult(JSONObject().put("params", params))
        assertEquals(mapOf("role" to "旁白", "emotion" to "平静"), result.params)
        assertEquals(0.9f, result.suggestedSpeed)
        assertEquals(false, result.fallback)
        // 越界语速建议丢弃；缺 params 时不崩
        assertEquals(null, decodeTtsAiParamsResult(
            JSONObject().put("params", JSONObject().put("suggested_speed", 9.9))).suggestedSpeed)
        assertEquals(0, decodeTtsAiParamsResult(JSONObject()).params.size)
        val failed = decodeTtsAiParamsResult(JSONObject().put("fallback", true).put("params", JSONObject()))
        assertEquals(true, failed.fallback)
    }

    @Test
    fun paramsResult_tolerates_null_literal_values() {
        val params = JSONObject()
        params.put("role", JSONObject.NULL)
        params.put("emotion", "温暖")
        val result = decodeTtsAiParamsResult(JSONObject().put("params", params))
        assertEquals(mapOf("emotion" to "温暖"), result.params)
    }

    @Test
    fun params_result_filter_fields_by_mode() {
        // 2026-09-23 四模式隔离的客户端侧：direct 结果混出的音色设计字段不进导演面板，
        // voice 结果混出的导演字段/情感列表不进音色设计面板（服务端白名单之外的双保险）
        val mixed = JSONObject().put("params", JSONObject()
            .put("role", "旁白").put("emotion", "平静")
            .put("texture", "温润").put("age", "青年"))
        val direct = decodeTtsAiParamsResult(mixed, TTS_AI_MODE_DIRECT)
        assertEquals(mapOf("role" to "旁白", "emotion" to "平静"), direct.params)
        // voice 字段白名单里本来就有 role（适合角色）：voice 结果保留它、丢掉情感列表的 emotion
        val voice = decodeTtsAiParamsResult(mixed, TTS_AI_MODE_VOICE)
        assertEquals(mapOf("role" to "旁白", "texture" to "温润", "age" to "青年"), voice.params)
        // mode 未指定（旧调用方）不过滤，保持兼容
        assertEquals(4, decodeTtsAiParamsResult(mixed).params.size)
        // 白名单只对 string 字段生效：suggested_speed 仍单列解析
        val withSpeed = JSONObject().put("params", JSONObject()
            .put("role", "旁白").put("suggested_speed", 1.2))
        assertEquals(1.2f, decodeTtsAiParamsResult(withSpeed, TTS_AI_MODE_DIRECT).suggestedSpeed)
    }

    @Test
    fun ai_field_keys_match_client_panel_fields() {
        // 与服务端 vivo_text 的 DIRECT_FIELDS/VOICE_FIELDS 契约一致（两端同键才回填得上）
        assertEquals(
            listOf("role", "scene", "goal", "emotion", "speed", "pause", "stress", "taboo"),
            TTS_DIRECTOR_FIELDS.map { it.key })
        assertEquals(
            listOf("age", "gender", "texture", "speed", "accent", "role", "mood", "tone",
                "persona", "position", "rhythm", "breath", "taboo"),
            TTS_VOICE_DESCRIPTION_FIELDS.map { it.key })
        assertTrue(ttsAiModeLabel(TTS_AI_MODE_SCRIPT).contains("脚本"))
        assertTrue(ttsAiModeLabel(TTS_AI_MODE_DIRECT).contains("导演"))
        assertTrue(ttsAiModeLabel(TTS_AI_MODE_VOICE).contains("音色"))
    }

    @Test
    fun voice_design_input_follows_mode() {
        // 优化模式：取用户描述；分析模式：取该角色台词上下文；都做裁剪与 600 字截断
        assertEquals("温柔女声", buildVoiceDesignAiInput(TTS_VOICE_AI_MODE_OPTIMIZE, " 温柔女声 ", "台词"))
        assertEquals("台词内容", buildVoiceDesignAiInput(TTS_VOICE_AI_MODE_ANALYZE, "", " 台词内容 "))
        assertEquals(600, buildVoiceDesignAiInput(TTS_VOICE_AI_MODE_ANALYZE, "", "x".repeat(1000)).length)
        assertEquals("", buildVoiceDesignAiInput(TTS_VOICE_AI_MODE_OPTIMIZE, "  ", "台词"))
    }
    @Test
    fun emotion_guard_rejects_rewritten_lines() {
        val original = "旁白：月光洒在湖面上。\n小雨：晚安。"
        assertTrue(emotionTagsPreserveLines(original, "旁白（平静）：月光洒在湖面上。\n小雨（温柔）：晚安。"))
        // 台词被改写 → 拒绝应用
        assertFalse(emotionTagsPreserveLines(original, "旁白（平静）：这是一个关于成长的故事。\n小雨（温柔）：晚安。"))
        // 增删行 → 拒绝应用
        assertFalse(emotionTagsPreserveLines(original, "旁白（平静）：月光洒在湖面上。"))
    }

    @Test
    fun emotion_tags_parsed_per_line_and_aligned() {
        val lines = listOf("月光洒在湖面上。", "晚安，妈妈。", "他说：快跑！")
        val tags = parseEmotionTags(lines, "旁白（平静）：月光洒在湖面上。\n小雨（温柔）：晚安，妈妈。\n旁白（紧张）：他说：快跑！")
        assertEquals(listOf("平静", "温柔", "紧张"), tags)
        // 未标注的行给空标签（合成时回退脚本内标注）
        val partial = parseEmotionTags(lines, "旁白（平静）：月光洒在湖面上。\n小雨：晚安，妈妈。\n旁白：他说：快跑！")
        assertEquals(listOf("平静", "", ""), partial)
        // 容忍无「角色：」前缀的行尾（情绪）写法（保留原文句读）
        val lineEnd = parseEmotionTags(lines, "月光洒在湖面上。（平静）\n晚安，妈妈。（温柔）\n旁白：他说：快跑！")
        assertEquals(listOf("平静", "温柔", ""), lineEnd)
        // 台词自带冒号、情绪标在行尾：冒号被误当前缀时回退行尾解释
        val colonLine = parseEmotionTags(listOf("他说：快跑！"), "他说：快跑！（紧张）")
        assertEquals(listOf("紧张"), colonLine)
    }

    @Test
    fun emotion_tags_reject_misaligned_or_rewritten_output() {
        val lines = listOf("月光洒在湖面上。", "晚安。")
        // 行数不一致
        assertNull(parseEmotionTags(lines, "旁白（平静）：月光洒在湖面上。"))
        // 台词被改写
        assertNull(parseEmotionTags(lines, "旁白（平静）：月光洒在湖面上。\n旁白（平静）：晚安呀。"))
        // 前缀位置错了（情绪标注跑到台词里）
        assertNull(parseEmotionTags(lines, "旁白：月光洒在湖面上（平静）。\n旁白（平静）：晚安。"))
    }
}
