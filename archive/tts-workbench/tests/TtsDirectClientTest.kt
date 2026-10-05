package com.example.local_music_player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TTS 直连执行器纯函数：票据解码、JSON 路径提取。
 * 【2026-10-05 归档】随 TTS 工作台源码一并存档，不参与编译。 */
class TtsDirectClientTest {

    @Test
    fun mimo_ticket_decodes_endpoint_headers_body_and_paths() {
        val payload = JSONObject()
            .put("direct", true)
            .put("kind", "mimo")
            .put("format", "mp3")
            .put("endpoint", "https://api.xiaomimimo.com/v1/chat/completions")
            .put("headers", JSONObject().put("Authorization", "Bearer sk-x").put("Content-Type", "application/json"))
            .put("body", JSONObject().put("model", "mimo-v2.5-tts").put("audio", JSONObject().put("format", "mp3")))
            .put("audio_paths", JSONArray(listOf("choices.0.message.audio.data", "choices.0.message.content")))
        val ticket = decodeTtsDirectTicket(payload)
        assertEquals("mimo", ticket?.kind)
        assertEquals("mp3", ticket?.format)
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", ticket?.endpoint)
        assertEquals("Bearer sk-x", ticket?.headers?.get("Authorization"))
        assertEquals("mimo-v2.5-tts", ticket?.body?.optString("model"))
        assertEquals(2, ticket?.audioPaths?.size)
        assertTrue(ticket?.url.isNullOrBlank())
    }

    @Test
    fun ticket_rejected_when_not_direct_or_missing_kind() {
        assertNull(decodeTtsDirectTicket(JSONObject().put("kind", "mimo")))
        assertNull(decodeTtsDirectTicket(JSONObject().put("direct", true)))
    }

    @Test
    fun json_path_walks_objects_arrays_and_reports_missing() {
        val json = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put(
                "audio", JSONObject().put("data", "QUJD")))))
        assertEquals("QUJD", walkJsonPath(json, "choices.0.message.audio.data"))
        assertNull(walkJsonPath(json, "choices.1.message.audio.data"))
        assertNull(walkJsonPath(json, "choices.0.message.audio.missing"))
        assertNull(walkJsonPath(json, "choices.zero.message"))
    }
}
