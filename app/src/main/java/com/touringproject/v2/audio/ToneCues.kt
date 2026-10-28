package com.touringproject.v2.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.touringproject.v2.ask.Cues
import com.touringproject.v2.settings.DEFAULT_START_CUE_MS

enum class Cue(val tone: Int, val durationMs: Int, val stream: Int) {
  // Intercoms drop the first part of a sound: a 1 s tone is heard as a short blip, so the start cue is longer. The
  // rider may change its length (SC-11); this is the default. It plays on the voice-call stream, which is what the
  // intercom hears while SCO is up.
  START(ToneGenerator.TONE_SUP_DIAL, DEFAULT_START_CUE_MS, AudioManager.STREAM_VOICE_CALL),
  // The rest play on the media stream: heard over the intercom's A2DP after returning to the nav app.
  SENT(ToneGenerator.TONE_DTMF_D, 800, AudioManager.STREAM_MUSIC),
  WAITING(ToneGenerator.TONE_DTMF_1, 600, AudioManager.STREAM_MUSIC),
  FAILURE(ToneGenerator.TONE_SUP_BUSY, 1_200, AudioManager.STREAM_MUSIC),
}

// Continuous tones only: the TONE_PROP_* tones have a built-in length (~0.16 s) that the duration cannot extend.
class ToneCues(private val makeGenerator: (stream: Int) -> ToneGenerator = { ToneGenerator(it, VOLUME) }) : Cues {
  private val main = Handler(Looper.getMainLooper())

  fun play(cue: Cue, durationMs: Int = cue.durationMs): Boolean {
    val generator = try {
      makeGenerator(cue.stream)
    } catch (e: RuntimeException) {
      Log.w(TAG, "ToneGenerator failed", e)
      return false
    }
    val started = generator.startTone(cue.tone, durationMs)
    main.postDelayed({ generator.release() }, durationMs + 200L)
    Log.i(TAG, "cue ${cue.name} started=$started")
    return started
  }

  /** SC-11's 「なし」 is 0: no start cue at all. */
  override fun start(durationMs: Int) {
    if (durationMs > 0) play(Cue.START, durationMs)
  }
  override fun sent() { play(Cue.SENT) }
  override fun waiting() { play(Cue.WAITING) }
  override fun failure() { play(Cue.FAILURE) }

  private companion object {
    const val TAG = "trv2.ride"
    const val VOLUME = 90
  }
}
