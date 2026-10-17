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
import org.robolectric.annotation.Config
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

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

class ClipSequenceTest {
  @Test
  fun a_stop_between_two_clips_keeps_the_next_one_silent() = runTest {
    val sequence = ClipSequence()
    val played = mutableListOf<String>()
    // The stop arrives after the first clip has ended and before the second one starts.
    sequence.play(listOf("1", "2", "3")) { url -> played += url; if (url == "1") sequence.stop() }
    assertEquals(listOf("1"), played)
  }

  @Test
  fun without_a_stop_every_clip_plays_in_order() = runTest {
    val played = mutableListOf<String>()
    ClipSequence().play(listOf("1", "2")) { played += it }
    assertEquals(listOf("1", "2"), played)
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

  @Test
  @Config(sdk = [30])
  fun before_android_12_the_screen_route_reports_no_intercom_instead_of_crashing() {
    // acquireSco switches to the main thread, so it is called from another thread while this one runs the main looper.
    var result: RouteResult? = null
    val route = AudioRoute(context)
    Thread { result = kotlinx.coroutines.runBlocking { route.acquireSco(1_000) } }.start()
    repeat(400) {
      if (result == null) {
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(5)
      }
    }
    assertFalse(result!!.ok)
  }
}

@RunWith(AndroidJUnit4::class)
class TtsSpeechTest {
  @Test
  fun a_new_message_releases_the_one_it_flushes(): Unit = kotlinx.coroutines.runBlocking {
    val speech = TtsSpeech(ApplicationProvider.getApplicationContext())
    val tts = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance()
    org.robolectric.Shadows.shadowOf(tts).onInitListener.onInit(android.speech.tts.TextToSpeech.SUCCESS)
    val first = async(kotlinx.coroutines.Dispatchers.Default) { speech.say("いま応答中です") }
    var waited = 0
    while (org.robolectric.Shadows.shadowOf(tts).lastSpokenText != "いま応答中です" && waited++ < 200) Thread.sleep(10)
    // QUEUE_FLUSH drops the first utterance; some engines never report onStop for it.
    val second = async(kotlinx.coroutines.Dispatchers.Default) { speech.say("録音できませんでした。") }
    kotlinx.coroutines.withTimeout(5_000) { first.await() }
    second.cancel()
  }
}
