package com.touringproject.v2.ask

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PollingTest {
  private val processing = AskResult("a1", "processing")
  private val completed = AskResult("a1", "completed", answer = "x")

  private class Clock {
    var now = 0L
    val sleeps = mutableListOf<Long>()
    suspend fun sleep(ms: Long) { sleeps += ms; now += ms }
  }

  private suspend fun poll(clock: Clock, cancelled: () -> Boolean = { false }, get: suspend () -> AskResult) =
    pollUntilDone(get, clock::sleep, { clock.now }, cancelled)

  @Test
  fun delays_are_1s_for_10s_then_2s() {
    assertEquals(1_000L, nextDelay(0))
    assertEquals(1_000L, nextDelay(9_999))
    assertEquals(2_000L, nextDelay(10_000))
  }

  @Test
  fun returns_the_first_finished_result() = runTest {
    val answers = mutableListOf(processing, processing, completed)
    val clock = Clock()
    assertEquals(PollOutcome.Done(completed), poll(clock) { answers.removeAt(0) })
    assertEquals(listOf(1_000L, 1_000L, 1_000L), clock.sleeps)
  }

  @Test
  fun transient_errors_are_skipped() = runTest {
    var calls = 0
    val outcome = poll(Clock()) { if (++calls == 1) throw ApiError(ApiErrorKind.SERVER) else completed }
    assertEquals(PollOutcome.Done(completed), outcome)
  }

  @Test
  fun invalid_key_stops_polling() = runTest {
    val error = runCatching { poll(Clock()) { throw ApiError(ApiErrorKind.INVALID_KEY) } }.exceptionOrNull()
    assertEquals(ApiErrorKind.INVALID_KEY, (error as ApiError).kind)
  }

  @Test
  fun gives_up_after_two_minutes() = runTest {
    val clock = Clock()
    assertEquals(PollOutcome.Timeout, poll(clock) { processing })
    assertEquals(POLL_TIMEOUT_MS, clock.now)
  }

  @Test
  fun cancellation_stops_polling() = runTest {
    var calls = 0
    assertEquals(PollOutcome.Cancelled, poll(Clock(), cancelled = { true }) { calls++; processing })
    assertEquals(0, calls)
  }
}

class ConversationTest {
  @Test
  fun conversation_continues_until_it_expires() {
    val c = ConversationState()
    c.update("c1", "2026-01-01T00:15:00Z")
    val expiry = java.time.Instant.parse("2026-01-01T00:15:00Z").toEpochMilli()
    assertEquals("c1", c.current(expiry - 1))
    assertEquals(expiry, c.expiresAt(expiry - 1))
    assertNull(c.current(expiry))
    assertNull(c.current(expiry - 1))
  }

  @Test
  fun reset_starts_a_new_conversation() {
    val c = ConversationState()
    c.update("c1", "2999-01-01T00:00:00Z")
    c.reset()
    assertNull(c.current(0))
    assertNull(c.expiresAt(0))
  }

  @Test
  fun unparseable_expiry_means_no_conversation() {
    val c = ConversationState()
    c.update("c1", "not a date")
    assertNull(c.current(0))
  }
}
