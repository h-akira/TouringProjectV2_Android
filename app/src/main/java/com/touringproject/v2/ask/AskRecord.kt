package com.touringproject.v2.ask

import com.touringproject.v2.api.AskResult
import com.touringproject.v2.api.HeadingSample
import com.touringproject.v2.location.Fix
import java.time.OffsetDateTime

// What the device keeps of an ask (slice 2 spec §5.2). The Backend makes the record; the app keeps its own copy and
// never sends it back (07 UC-7).

/** OFFLINE: the ask never reached the Backend (US-C-09); only when and where it was is kept. */
enum class AskOutcome { ANSWERED, FAILED, OFFLINE }

/** Slice 2 asks only by voice; text asks come in slice 4. */
const val ASK_INPUT_VOICE = "voice"

const val TIMEOUT_FAILURE = "timeout"

data class AskRecord(
  val askId: String,
  val askedAt: Long,
  /** When the answer arrived; null for a failed ask. */
  val answeredAt: Long?,
  val transcript: String?,
  val answer: String?,
  val latitude: Double?,
  val longitude: Double?,
  val address: String?,
  val headingDegrees: Double?,
  val input: String,
  /** null for an unreached ask: it never joined a conversation (US-C-09.006). */
  val conversationId: String?,
  /** True for the first ask of a conversation: the conversation breaks just before it (US-C-03.004). */
  val newConversation: Boolean,
  val outcome: AskOutcome,
  /** The Backend's error, or "timeout"; null when answered. */
  val failure: String?,
  /** The ride that was recording when the ask started (slice 2 spec §4.6). */
  val rideId: String?,
  /** The conversation's memo (US-D-04.001). The ask loop never sets it; only SC-08 does, through AskEdits. */
  val memo: String? = null,
  /** The older point the Backend takes an unreached ask's heading from (slice 4 spec §4.2); null for the other asks. */
  val sample: HeadingSample? = null,
  /** When an unreached ask last changed: the version the Backend compares. null for the asks the Backend made. */
  val updatedAt: Long? = null,
  /** False until an unreached ask has reached the Backend; the asks the Backend made are there from the start. */
  val sent: Boolean = true,
)

/**
 * An ask that never reached the Backend (slice 4 spec §4): when the recording ended, where, and the sample for the
 * heading. Its id is the device's; nothing it said is kept, and it joins no conversation.
 */
fun unreachedAsk(askId: String, at: Long, here: Fix?, sample: HeadingSample?, rideId: String) = AskRecord(
  askId = askId,
  askedAt = at,
  answeredAt = null,
  transcript = null,
  answer = null,
  latitude = here?.latitude,
  longitude = here?.longitude,
  address = null,
  headingDegrees = null,
  input = ASK_INPUT_VOICE,
  conversationId = null,
  newConversation = false,
  outcome = AskOutcome.OFFLINE,
  failure = null,
  rideId = rideId,
  sample = sample,
  updatedAt = at,
  sent = false,
)

/** What the loop knows once the Backend has taken the ask in. */
data class SentAsk(
  val askId: String,
  val conversationId: String,
  val newConversation: Boolean,
  val sentAt: Long,
  val here: Fix?,
  val rideId: String?,
)

/** An answer the rider can hear: a completed one, or text whose speech the Backend failed to make. */
fun readable(result: AskResult): Boolean = result.status == "completed" || (result.error == "tts_failed" && !result.answer.isNullOrEmpty())

/** result is the finished result, or null when polling gave up at 120 s (kept as a timeout). */
fun askRecord(sent: SentAsk, result: AskResult?, now: Long): AskRecord {
  val answered = result != null && readable(result)
  return AskRecord(
    askId = sent.askId,
    askedAt = result?.askedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: sent.sentAt,
    answeredAt = if (answered) now else null,
    transcript = result?.transcript,
    answer = result?.answer,
    latitude = sent.here?.latitude,
    longitude = sent.here?.longitude,
    address = result?.address,
    headingDegrees = result?.headingDegrees,
    input = ASK_INPUT_VOICE,
    conversationId = sent.conversationId,
    newConversation = sent.newConversation,
    outcome = if (answered) AskOutcome.ANSWERED else AskOutcome.FAILED,
    failure = when {
      answered -> null
      result == null -> TIMEOUT_FAILURE
      else -> result.error ?: "agent_failed"
    },
    rideId = sent.rideId,
  )
}
