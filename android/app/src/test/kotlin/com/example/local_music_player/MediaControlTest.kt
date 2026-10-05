package com.example.local_music_player

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaControlTest {
    @Test
    fun maps_headset_media_buttons_to_transport_commands() {
        assertEquals(MediaButtonAction.Play, mediaButtonAction(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(MediaButtonAction.Pause, mediaButtonAction(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(MediaButtonAction.Toggle, mediaButtonAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertNull(mediaButtonAction(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
