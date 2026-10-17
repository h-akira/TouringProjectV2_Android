package com.touringproject.v2.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.touringproject.v2.ask.Recorded
import com.touringproject.v2.ask.Recorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

// 16 kHz mono 16-bit PCM in memory (adr/0002). 20 s is about 640 KB.
@SuppressLint("MissingPermission") // RECORD_AUDIO is checked by the ask loop before it starts recording
class PcmRecorder(private val now: () -> Long = System::currentTimeMillis) : Recorder {
  private var record: AudioRecord? = null
  private var reader: Thread? = null
  private var buffer = ByteArrayOutputStream()
  private var name: String? = null

  override suspend fun start() = withContext(Dispatchers.IO) {
    val minSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val ar = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minSize * 4)
    if (ar.state != AudioRecord.STATE_INITIALIZED) {
      ar.release()
      error("AudioRecord not initialized")
    }
    val out = ByteArrayOutputStream()
    ar.startRecording()
    val thread = Thread {
      val chunk = ByteArray(minSize)
      while (ar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
        val n = ar.read(chunk, 0, chunk.size)
        if (n > 0) synchronized(out) { out.write(chunk, 0, n) } else if (n < 0) break
      }
    }
    thread.start()
    record = ar
    reader = thread
    buffer = out
    name = "rec-${now()}.pcm"
  }

  override suspend fun stop(): Recorded = withContext(Dispatchers.IO) {
    val ar = checkNotNull(record) { "not recording" }
    finish(ar)
    val pcm = synchronized(buffer) { buffer.toByteArray() }
    Recorded(pcm, checkNotNull(name)).also { name = null }
  }

  override suspend fun cancel() = withContext(Dispatchers.IO) {
    record?.let { finish(it) }
    name = null
  }

  private fun finish(ar: AudioRecord) {
    runCatching { ar.stop() }
    reader?.join(1_000)
    ar.release()
    record = null
    reader = null
  }

  private companion object {
    const val SAMPLE_RATE = 16_000
  }
}
