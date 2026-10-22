package com.touringproject.v2.store

import com.touringproject.v2.ask.AskLog
import com.touringproject.v2.ask.AskOutcome
import com.touringproject.v2.ask.AskRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The memo is not copied: the loop writes an ask once, at its end, and SC-08 changes its memo apart (AskDao.writeMemo),
 * so the row starts without one (Android #55).
 */
fun AskRecord.toEntity() = AskEntity(
  askId = askId,
  askedAt = askedAt,
  answeredAt = answeredAt,
  transcript = transcript,
  answer = answer,
  latitude = latitude,
  longitude = longitude,
  address = address,
  headingDegrees = headingDegrees,
  input = input,
  conversationId = conversationId,
  newConversation = newConversation,
  status = outcome.name.lowercase(),
  failure = failure,
  rideId = rideId,
)

/** Keeps asks in Room without holding up the ask loop. A failed write is logged and shown, like a ride's (slice 2 spec §8). */
class RoomAskLog(private val dao: AskDao, private val scope: CoroutineScope, private val log: (String, Throwable) -> Unit) : AskLog {
  private val failing = MutableStateFlow(false)
  /** True while the last write of an ask failed. */
  val failed: StateFlow<Boolean> = failing.asStateFlow()

  override fun keep(record: AskRecord) {
    scope.launch { save(record) }
  }

  /** An ask whose ride was deleted meanwhile is dropped (Android #54). */
  suspend fun save(record: AskRecord) {
    try {
      dao.keep(record.toEntity())
      failing.value = false
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("ask_store_failed askId=${record.askId}", e)
      failing.value = true
    }
  }
}
