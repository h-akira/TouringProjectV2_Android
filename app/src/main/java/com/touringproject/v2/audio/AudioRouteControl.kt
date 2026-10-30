package com.touringproject.v2.audio

import android.util.Log

private const val VR_TIMEOUT_MS = 4_000L
private const val SCO_TIMEOUT_MS = 3_000L

// The intercom button arrives as a voice-recognition request from the headset; the screen button opens a virtual call.
class AudioRouteControl(private val route: AudioRoute) : RouteDriver {
  private var source: Source? = null

  override suspend fun start(source: Source): Boolean {
    this.source = source
    val result = if (source == Source.HANDSFREE) route.startVoiceRecognition(VR_TIMEOUT_MS) else route.acquireSco(SCO_TIMEOUT_MS)
    Log.i("trv2.route", "route source=$source ok=${result.ok} note=${result.note}")
    return result.ok
  }

  override suspend fun stop() {
    if (source == Source.HANDSFREE) route.stopVoiceRecognition("done") else route.releaseSco()
    source = null
  }

  override fun inputDevice(): InputDevice? = route.activeInputDevice()
}
