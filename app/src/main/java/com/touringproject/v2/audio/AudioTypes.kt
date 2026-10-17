package com.touringproject.v2.audio

import kotlinx.serialization.Serializable

// Values the ask loop reads from the audio side; kept apart from AudioRoute so the loop compiles without it.
data class MediaVolume(val current: Int, val max: Int)

@Serializable
data class InputDevice(val type: Type, val name: String) {
  enum class Type { BLUETOOTH, BUILTIN, OTHER }
}

data class RouteResult(val ok: Boolean, val note: String)
