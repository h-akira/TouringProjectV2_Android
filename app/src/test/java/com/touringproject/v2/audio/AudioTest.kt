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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import android.media.AudioDeviceInfo
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadow.api.Shadow
import java.time.Duration

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

// SC-11: the start cue sounds as long as chosen, and not at all for 「なし」.
@RunWith(AndroidJUnit4::class)
class ToneCuesTest {
  @Test
  fun the_start_cue_takes_the_chosen_length_and_none_sounds_nothing() {
    val streams = mutableListOf<Int>()
    // The generator is made per cue; failing here keeps the test off the real ToneGenerator.
    val cues = ToneCues { stream -> streams += stream; throw RuntimeException("no tone in tests") }
    cues.start(0)
    assertEquals(emptyList<Int>(), streams)
    cues.start(500)
    assertEquals(listOf(AudioManager.STREAM_VOICE_CALL), streams)
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
  fun a_low_media_volume_raises_the_warning() {
    // 4b #2: a third of the maximum or more is fine, below it the band shows.
    audioManager.mode = AudioManager.MODE_NORMAL
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max / 3 - 1, 0)
    assertTrue(com.touringproject.v2.ui.volumeWarning(AudioRoute(context).mediaVolume(), null))
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
    assertFalse(com.touringproject.v2.ui.volumeWarning(AudioRoute(context).mediaVolume(), null))
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
class PcmPlayerTest {
  // The shadow track takes every write at once and counts it as played, so stopping midway is checked on the device.
  @Test
  fun a_recording_is_played_to_the_end_and_can_be_played_again(): Unit = kotlinx.coroutines.runBlocking {
    val player = PcmPlayer()
    kotlinx.coroutines.withTimeout(5_000) {
      player.play(ByteArray(3_200))
      player.play(ByteArray(3_200))
    }
  }

  @Test
  fun stopping_when_nothing_plays_is_harmless() {
    PcmPlayer().stop()
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

/** An audio manager whose call route never comes up by itself: the request is taken, the device is not set. */
@Implements(value = AudioManager::class, isInAndroidSdk = false)
class PendingScoAudioManager : ShadowAudioManager() {
  var requested = 0
  var cleared = 0

  @Implementation
  override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean {
    requested += 1
    return true
  }

  @Implementation
  override fun clearCommunicationDevice() {
    cleared += 1
    super.clearCommunicationDevice()
  }
}

// Android R1-03: a route's setup that is called off is undone at once, not left to run on inside AudioRoute.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], shadows = [PendingScoAudioManager::class])
class AudioRouteCancelTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val audioManager = context.getSystemService(AudioManager::class.java)
  private val shadow = Shadow.extract<PendingScoAudioManager>(audioManager)
  private val scope = CoroutineScope(Dispatchers.Default)

  private fun idle(ms: Long = 0) {
    val looper = shadowOf(Looper.getMainLooper())
    if (ms > 0) looper.idleFor(Duration.ofMillis(ms)) else looper.idle()
  }

  /** The main looper is paused, so the test thread runs it while the coroutine waits on its own thread. */
  private fun pumpUntil(condition: () -> Boolean) {
    repeat(400) {
      if (condition()) return
      idle()
      Thread.sleep(5)
    }
    check(condition()) { "condition never became true" }
  }

  @Test
  fun a_screen_route_setup_that_is_called_off_clears_the_call_route_at_once_and_leaves_nothing_for_the_timeout() {
    val sco = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
    shadow.setAvailableCommunicationDevices(listOf(sco))
    val route = AudioRoute(context)
    val job = scope.launch { route.acquireSco(3_000) }
    pumpUntil { shadow.requested == 1 }
    assertEquals(0, shadow.cleared)
    runBlocking {
      scope.launch { job.cancelAndJoin() }
      pumpUntil { job.isCompleted }
    }
    assertEquals(1, shadow.cleared)
    assertEquals(AudioManager.MODE_NORMAL, audioManager.mode)
    // The 3 s timeout must not come back to clear the route of whoever has it by then.
    idle(5_000)
    assertEquals(1, shadow.cleared)
  }
}
