package com.touringproject.v2.audio

// Values the ask loop reads from the audio side; kept apart from AudioRoute so the loop compiles without it.
data class MediaVolume(val current: Int, val max: Int)

data class InputDevice(val type: Type, val name: String) {
  enum class Type { BLUETOOTH, BUILTIN, OTHER }
}

data class RouteResult(val ok: Boolean, val note: String)
