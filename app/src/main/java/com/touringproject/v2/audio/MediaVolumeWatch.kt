package com.touringproject.v2.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The media volume as Home and the ask loop know it, for the life of the process (slice 1 spec §5.6). A withheld
 * reading (null while the intercom's call link lingers after an ask) keeps the last good one, so a screen made anew
 * and the next ask still have it (Android #9). The rider's ✕ on the low-volume band lasts until another volume is
 * read (Android #8). Call it from the main thread.
 */
class MediaVolumeWatch(private val read: () -> MediaVolume?) {
  private val volumeState = MutableStateFlow<MediaVolume?>(null)
  /** The last good reading; null until one has been had. */
  val volume: StateFlow<MediaVolume?> = volumeState.asStateFlow()

  private val dismissed = MutableStateFlow<Int?>(null)
  /** The volume at which the rider closed the low-volume band; null once another one has been read. */
  val dismissedAt: StateFlow<Int?> = dismissed.asStateFlow()

  /** Reads the volume now, and returns the last good one when the reading is withheld or fails. */
  fun refresh(): MediaVolume? {
    val reading = runCatching { read() }.getOrNull()
    if (reading != null && dismissed.value != null && reading.current != dismissed.value) dismissed.value = null
    volumeState.value = reading ?: volumeState.value
    return volumeState.value
  }

  fun dismiss(level: Int) {
    dismissed.value = level
  }
}
