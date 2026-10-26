package com.touringproject.v2.audio

import com.touringproject.v2.ui.volumeWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Slice 1 spec §5.6: the media volume Home and SC-03 warn about.
class MediaVolumeWatchTest {
  private val readings = ArrayDeque<MediaVolume?>()
  private val watch = MediaVolumeWatch { readings.removeFirstOrNull() }

  private fun read(vararg next: MediaVolume?): MediaVolume? {
    readings.addAll(next)
    var last: MediaVolume? = null
    repeat(next.size) { last = watch.refresh() }
    return last
  }

  // Android #9: AudioRoute.mediaVolume() is null while media still routes to the intercom's call link; the last good
  // reading stays, for Home and for the next ask alike.
  @Test
  fun a_withheld_reading_keeps_the_last_good_one() {
    assertNull(read(null))
    assertEquals(MediaVolume(10, 15), read(MediaVolume(10, 15), null, null))
    assertEquals(MediaVolume(10, 15), watch.volume.value)
    assertEquals(MediaVolume(3, 15), read(MediaVolume(3, 15)))
  }

  @Test
  fun a_reading_that_fails_keeps_the_last_good_one() {
    read(MediaVolume(2, 15))
    val failing = MediaVolumeWatch { error("no audio manager") }
    assertNull(failing.refresh())
    assertEquals(MediaVolume(2, 15), watch.refresh())
  }

  // Android #8: closed at 3, then 4, then back at 3: the band comes back.
  @Test
  fun a_dismissal_lasts_until_another_volume_is_read() {
    read(MediaVolume(3, 15))
    watch.dismiss(3)
    assertFalse(volumeWarning(read(MediaVolume(3, 15)), watch.dismissedAt.value))
    // A withheld reading is no change of volume.
    assertFalse(volumeWarning(read(null), watch.dismissedAt.value))
    read(MediaVolume(4, 15))
    assertNull(watch.dismissedAt.value)
    assertTrue(volumeWarning(read(MediaVolume(3, 15)), watch.dismissedAt.value))
  }
}
