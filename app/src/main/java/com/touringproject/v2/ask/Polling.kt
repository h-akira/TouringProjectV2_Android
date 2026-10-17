package com.touringproject.v2.ask

import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.api.AskResult

// Poll schedule from 07 UC-5: every 1 s for 10 s, then every 2 s, give up at 120 s.
const val POLL_FAST_MS = 1_000L
const val POLL_SLOW_MS = 2_000L
const val FAST_WINDOW_MS = 10_000L
const val POLL_TIMEOUT_MS = 120_000L
private val FATAL = setOf(ApiErrorKind.INVALID_KEY, ApiErrorKind.BAD_REQUEST, ApiErrorKind.NOT_FOUND)

fun nextDelay(elapsedMs: Long): Long = if (elapsedMs < FAST_WINDOW_MS) POLL_FAST_MS else POLL_SLOW_MS

sealed interface PollOutcome {
  data class Done(val result: AskResult) : PollOutcome
  data object Timeout : PollOutcome
  data object Cancelled : PollOutcome
}

suspend fun pollUntilDone(
  get: suspend () -> AskResult,
  sleep: suspend (Long) -> Unit,
  now: () -> Long,
  isCancelled: () -> Boolean,
): PollOutcome {
  val startedAt = now()
  while (true) {
    val elapsed = now() - startedAt
    if (elapsed >= POLL_TIMEOUT_MS) return PollOutcome.Timeout
    sleep(nextDelay(elapsed))
    if (isCancelled()) return PollOutcome.Cancelled
    try {
      val result = get()
      if (result.status != "processing") return PollOutcome.Done(result)
    } catch (e: ApiError) {
      if (e.kind in FATAL) throw e
    }
  }
}
