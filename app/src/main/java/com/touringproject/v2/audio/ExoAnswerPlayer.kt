package com.touringproject.v2.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.touringproject.v2.ask.AnswerPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val CLIP_STALL_MS = 15_000L
const val STALL_CHECK_MS = 1_000L

/** Gives up on a clip whose position has not moved for CLIP_STALL_MS (a stalled stream reports nothing on its own). */
class StallWatch(private val stallMs: Long = CLIP_STALL_MS) {
  private var lastPosition = -1L
  private var idleMs = 0L

  /** Called every [elapsedMs] with the current position; true once the clip counts as stalled. */
  fun tick(positionMs: Long, elapsedMs: Long): Boolean {
    if (positionMs != lastPosition) {
      lastPosition = positionMs
      idleMs = 0
      return false
    }
    idleMs += elapsedMs
    return idleMs >= stallMs
  }
}

// Plays the answer clips one after another on the media stream, without taking audio focus (mix with the nav app).
class ExoAnswerPlayer(private val context: Context) : AnswerPlayer {
  private val main = Handler(Looper.getMainLooper())
  private var stopCurrent: (() -> Unit)? = null

  private class Session {
    var stopped = false
  }

  override suspend fun play(urls: List<String>) = withContext(Dispatchers.Main) {
    val session = Session()
    for (url in urls) {
      if (session.stopped) return@withContext
      playOne(url, session)
    }
  }

  override fun stop() {
    main.post { stopCurrent?.invoke() }
  }

  private suspend fun playOne(url: String, session: Session) = suspendCancellableCoroutine { cont ->
    val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build()
    val player = ExoPlayer.Builder(context).setAudioAttributes(attributes, false).build()
    val watch = StallWatch()
    var settled = false
    lateinit var stop: () -> Unit
    lateinit var check: Runnable
    fun finish(error: Throwable?) {
      if (settled) return
      settled = true
      main.removeCallbacks(check)
      player.release()
      // Another playback may have taken over stopCurrent meanwhile; its stop must stay reachable.
      if (stopCurrent === stop) stopCurrent = null
      if (error != null) cont.resumeWithException(error) else cont.resume(Unit)
    }
    check = Runnable {
      if (watch.tick(player.currentPosition, STALL_CHECK_MS)) finish(IllegalStateException("stalled")) else main.postDelayed(check, STALL_CHECK_MS)
    }
    stop = {
      session.stopped = true
      finish(null)
    }
    player.addListener(object : Player.Listener {
      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) finish(null)
      }

      override fun onPlayerError(error: PlaybackException) = finish(error)
    })
    stopCurrent = stop
    cont.invokeOnCancellation { main.post { finish(null) } }
    player.setMediaItem(MediaItem.fromUri(url))
    player.prepare()
    player.play()
    main.postDelayed(check, STALL_CHECK_MS)
  }
}
