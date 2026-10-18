package com.touringproject.v2.service

import com.touringproject.v2.location.RouteSource
import com.touringproject.v2.location.Subscription
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingWatchTest {
  private var watches = 0
  private var removed = 0
  private var refuse = false
  private val heartbeats = mutableListOf<Job>()
  private val errors = mutableListOf<String>()
  private val source = RouteSource {
    watches += 1
    if (refuse) throw SecurityException("no location permission")
    Subscription { removed += 1 }
  }
  private val watch = RecordingWatch(source, {}, { Job().also { heartbeats += it } }) { message, _ -> errors += message }

  @Test
  fun a_watch_that_cannot_start_is_tried_again_but_the_heartbeat_runs_once() {
    refuse = true
    watch.start()
    watch.start()
    assertEquals(2, watches)
    assertEquals(1, heartbeats.size)
    assertEquals(2, errors.size)
  }

  @Test
  fun starting_again_while_running_starts_nothing_and_stop_ends_both() {
    watch.start()
    watch.start()
    assertEquals(1, watches)
    assertEquals(1, heartbeats.size)
    watch.stop()
    assertEquals(1, removed)
    assertTrue(heartbeats.single().isCancelled)
    watch.start()
    assertEquals(2, heartbeats.size)
  }
}
