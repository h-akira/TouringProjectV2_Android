package com.touringproject.v2.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

class StallWatchTest {
  @Test
  fun a_clip_that_never_progresses_is_given_up() {
    val watch = StallWatch(stallMs = 3_000)
    assertFalse(watch.tick(0, 1_000)) // first reading
    assertFalse(watch.tick(0, 1_000))
    assertFalse(watch.tick(0, 1_000))
    assertTrue(watch.tick(0, 1_000))
  }

  @Test
  fun progress_keeps_the_watchdog_quiet() {
    val watch = StallWatch(stallMs = 3_000)
    (0L..20L).forEach { assertFalse(watch.tick(it * 1_000, 1_000)) }
  }
}

class CueTest {
  @Test
  fun the_start_cue_is_long_and_heard_on_the_call_stream() {
    assertEquals(AudioManager.STREAM_VOICE_CALL, Cue.START.stream)
    assertTrue(Cue.START.durationMs >= 1_500)
    // TONE_PROP_* tones stop after about 0.16 s whatever duration is asked for.
    val propTones = setOf(ToneGenerator.TONE_PROP_ACK, ToneGenerator.TONE_PROP_BEEP, ToneGenerator.TONE_PROP_NACK, ToneGenerator.TONE_PROP_PROMPT)
    assertTrue(Cue.entries.none { it.tone in propTones })
    assertTrue(Cue.entries.filter { it != Cue.START }.all { it.stream == AudioManager.STREAM_MUSIC })
  }
}

@RunWith(AndroidJUnit4::class)
class AudioRouteTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val audioManager = context.getSystemService(AudioManager::class.java)

  @Test
  fun media_volume_is_read_from_the_music_stream() {
    audioManager.mode = AudioManager.MODE_NORMAL
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, 0)
    val volume = AudioRoute(context).mediaVolume()!!
    assertEquals(4, volume.current)
    assertEquals(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), volume.max)
  }

  @Test
  fun media_volume_is_withheld_while_the_call_route_is_up() {
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    assertNull(AudioRoute(context).mediaVolume())
    audioManager.mode = AudioManager.MODE_NORMAL
  }
}
