package com.touringproject.v2.audio

// What the ask loop, SC-12's trial recording and SC-19's voice input use of the audio side: they come in through
// these interfaces, so each is testable on the JVM.

enum class Source { HANDSFREE, SCREEN }

const val TICK_MS = 250L

/** 16 kHz mono 16-bit PCM: 32 bytes per millisecond. */
fun pcmDurationMs(bytes: Int): Long = bytes / 32L

interface RouteControl {
  /** Brings up the intercom microphone; false means the phone's own microphone will be used. */
  suspend fun start(source: Source): Boolean
  suspend fun stop()
  /** The microphone of the recording that is running right now (only visible while it runs). */
  fun inputDevice(): InputDevice?
}

data class Recorded(val pcm: ByteArray, val name: String)

interface Recorder {
  suspend fun start()
  suspend fun stop(): Recorded
  suspend fun cancel()
}

interface Cues {
  /** The start cue, durationMs long (SC-11); 0 sounds none. */
  fun start(durationMs: Int)
  fun sent()
  fun waiting()
  fun failure()
}

interface AnswerPlayer {
  /** Plays the clips in order; throws when a clip fails or stalls. */
  suspend fun play(urls: List<String>)
  fun stop()
}

interface Speech {
  /** Returns when the text has been said, stopped, or failed. */
  suspend fun say(text: String)
  fun stop()
}

/** Plays recorded PCM; returns when it has been heard or stopped, throws when it cannot be played. */
interface PcmPlayback {
  suspend fun play(pcm: ByteArray)
  fun stop()
}
