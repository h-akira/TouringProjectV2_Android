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
import java.time.Instant

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
  updatedAt = updatedAt,
  sampleLatitude = sample?.latitude,
  sampleLongitude = sample?.longitude,
  sampleAt = sample?.let { Instant.parse(it.at).toEpochMilli() },
)

/**
 * Keeps asks in Room without holding up the ask loop. A failed write is logged and shown, like a ride's (slice 2 spec
 * §8); so is an ask dropped because its ride is not kept, without a throwable (Android #58). An unreached ask written
 * is something new to send (unreachedKept): the network may still be there, the Backend having failed (Android #64).
 */
class RoomAskLog(
  private val dao: AskDao,
  private val scope: CoroutineScope,
  private val unreachedKept: () -> Unit = {},
  private val log: (String, Throwable?) -> Unit,
) : AskLog {
  private val failing = MutableStateFlow(false)
  /** True once an ask could not be written, until the rider closes the band or the next ask is written (Android #28). */
  val failed: StateFlow<Boolean> = failing.asStateFlow()

  /** SC-02's ✕ on the band: the ask is lost either way, and the rider has seen it (Android #28). */
  fun dismiss() {
    failing.value = false
  }

  override fun keep(record: AskRecord) {
    scope.launch { save(record) }
  }

  /**
   * An ask whose ride is not kept is dropped (Android #54): deleted meanwhile, or never written. That leaves the warning
   * as it was, since nothing was written either way (Android #58).
   */
  suspend fun save(record: AskRecord) {
    try {
      if (!dao.keep(record.toEntity())) {
        log("ask_dropped askId=${record.askId} rideId=${record.rideId}", null)
        return
      }
      failing.value = false
      if (record.outcome == AskOutcome.OFFLINE) unreachedKept()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log("ask_store_failed askId=${record.askId}", e)
      failing.value = true
    }
  }
}
