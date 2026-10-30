package com.touringproject.v2.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// The device voice: failure messages, "いま応答中です…", and the answer text when its audio cannot be played.
class TtsSpeech(context: Context) : Speech {
  private val ready = CompletableDeferred<Boolean>()
  private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
  private val ids = AtomicInteger()
  private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

  init {
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String) = Unit
      override fun onDone(utteranceId: String) = settle(utteranceId)
      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String) = settle(utteranceId)
      override fun onError(utteranceId: String, errorCode: Int) = settle(utteranceId)
      override fun onStop(utteranceId: String, interrupted: Boolean) = settle(utteranceId)
    })
  }

  private fun settle(id: String) {
    pending.remove(id)?.complete(Unit)
  }

  override suspend fun say(text: String) {
    if (!ready.await()) return
    tts.language = Locale.JAPAN
    // QUEUE_FLUSH drops whatever is still being said; release its caller even if the engine reports nothing for it.
    pending.keys.toList().forEach { settle(it) }
    val id = "u${ids.incrementAndGet()}"
    val done = CompletableDeferred<Unit>()
    pending[id] = done
    if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) settle(id)
    try {
      done.await()
    } finally {
      // Cut short (the caller gave up waiting): nothing may stay in the table for an utterance nobody waits for.
      pending.remove(id)
    }
  }

  override fun stop() {
    tts.stop()
    // Some engines do not report onStop for a flushed utterance; nothing may stay waiting.
    pending.keys.toList().forEach { settle(it) }
  }
}
