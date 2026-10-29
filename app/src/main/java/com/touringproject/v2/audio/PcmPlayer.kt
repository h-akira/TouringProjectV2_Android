package com.touringproject.v2.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SAMPLE_RATE = 16_000
private const val CHUNK_BYTES = 8_192
// After the last write, the rest still in the track's buffer is waited for, plus this much slack.
private const val DRAIN_SLACK_MS = 1_000L

/**
 * Plays 16 kHz mono 16-bit PCM on the media stream (heard over the intercom's A2DP once the call route is down).
 * SC-12 makes one for the trial recording and another for the kept recordings, so stopping one never stops the other.
 */
class PcmPlayer(private val pollMs: Long = 50) : PcmPlayback {
  private val lock = Any()
  private var track: AudioTrack? = null
  @Volatile private var stopped = false

  override suspend fun play(pcm: ByteArray) {
    // Cleared before switching threads, so a stop() that follows this call is never undone.
    stopped = false
    withContext(Dispatchers.IO) { playOn(pcm) }
  }

  private suspend fun playOn(pcm: ByteArray) {
    val t = AudioTrack.Builder()
      .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
      .setAudioFormat(
        AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
      )
      .setTransferMode(AudioTrack.MODE_STREAM)
      .build()
    if (t.state != AudioTrack.STATE_INITIALIZED) {
      t.release()
      error("AudioTrack not initialized")
    }
    synchronized(lock) { track = t }
    try {
      t.play()
      var written = 0
      // write() blocks while the buffer is full; stop() pauses the track, which makes a blocked write return early.
      while (written < pcm.size && !stopped) {
        val n = t.write(pcm, written, minOf(CHUNK_BYTES, pcm.size - written))
        if (n < 0) error("AudioTrack write failed: $n")
        if (n == 0) break
        written += n
      }
      val frames = written / 2
      val deadline = System.currentTimeMillis() + frames * 1_000L / SAMPLE_RATE + DRAIN_SLACK_MS
      while (!stopped && t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) delay(pollMs)
    } finally {
      synchronized(lock) {
        track = null
        runCatching { t.stop() }
        t.release()
      }
    }
  }

  override fun stop() {
    stopped = true
    synchronized(lock) { runCatching { track?.pause() } }
  }
}
