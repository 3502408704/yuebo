package com.example.local_music_player

import android.speech.tts.TextToSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TtsEngineSelectionTest {
    private val engines = listOf(
        TtsEngineInfo("engine.first", "第一个引擎"),
        TtsEngineInfo("engine.saved", "已保存引擎"),
    )

    @Test
    fun keeps_saved_engine_when_it_is_installed() {
        assertEquals("engine.saved", chooseTtsEnginePackage("engine.saved", engines))
    }

    @Test
    fun falls_back_to_first_installed_engine_when_saved_engine_is_missing() {
        assertEquals("engine.first", chooseTtsEnginePackage("engine.missing", engines))
    }

    @Test
    fun returns_null_when_no_engine_is_installed() {
        assertNull(chooseTtsEnginePackage("engine.saved", emptyList()))
    }

    @Test
    fun probe_failure_reports_no_engines() {
        assertEquals(
            emptyList(),
            ttsEnginesFromProbe(TextToSpeech.ERROR, engines),
        )
    }

    @Test
    fun failed_utterance_is_retried_only_once() {
        assertTrue(shouldRetryLyricTtsFailure(0))
        assertFalse(shouldRetryLyricTtsFailure(1))
    }
}
