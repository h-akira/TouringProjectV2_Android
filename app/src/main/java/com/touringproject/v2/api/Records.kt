package com.touringproject.v2.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// The user's records as GET /records reads them back (slice 4 spec §6.2, docs-parent/07a_openapi.yaml RecordItem). The
// Backend writes a value it has not got as null and a whole number without its fraction, so numbers are read as Double.

sealed interface CloudRecord

/** A ride as it was last put: its meta, without its route. */
@Serializable
data class CloudRide(
  val rideId: String,
  val name: String,
  val status: String,
  val segments: List<SegmentBody>,
  val autoEnded: Boolean,
  val updatedAt: String,
  val memo: String? = null,
) : CloudRecord

/** One route chunk of a ride as it was last put. */
@Serializable data class CloudRoute(val rideId: String, val seq: Int, val points: List<PointBody>) : CloudRecord

/** A point memo with the place and heading the Backend settled. */
@Serializable
data class CloudNote(
  val rideId: String,
  val noteId: String,
  val at: String,
  val location: Point? = null,
  val text: String,
  val updatedAt: String,
  val address: String? = null,
  val headingDegrees: Double? = null,
) : CloudRecord

/**
 * An ask: one the Backend took in (status processing / completed / failed) or an unreached one (offline). question is a
 * text ask's question; updatedAt is set only for an unreached ask.
 */
@Serializable
data class CloudAsk(
  val askId: String,
  val status: String,
  val askedAt: String? = null,
  val question: String? = null,
  val transcript: String? = null,
  val answer: String? = null,
  val error: String? = null,
  val answeredAt: String? = null,
  val location: Point? = null,
  val address: String? = null,
  val headingDegrees: Double? = null,
  val inputType: String? = null,
  val conversationId: String? = null,
  val newConversation: Boolean = false,
  val rideId: String? = null,
  val memo: String? = null,
  val memoUpdatedAt: String? = null,
  val updatedAt: String? = null,
) : CloudRecord

/** A deletion's mark: the target (ask / ride / note) is gone, and a ride with all that was under it. */
@Serializable data class CloudDeleted(val target: String, val id: String, val rideId: String? = null, val deletedAt: String) : CloudRecord

/** One page; cursor is null on the last one. */
data class CloudPage(val items: List<CloudRecord>, val cursor: String?)

interface RecordsApi {
  /** The page after cursor, or the first one for null. A record of a known kind that cannot be read throws. */
  suspend fun records(cursor: String?): CloudPage
}

@Serializable internal data class RecordsPageBody(val items: List<JsonObject>, val cursor: String? = null)

/** A kind the device does not know (one the Backend may add later) is left out rather than failing the page. */
internal fun cloudRecord(json: Json, item: JsonObject): CloudRecord? = when (item["kind"]?.jsonPrimitive?.contentOrNull) {
  "ride" -> json.decodeFromJsonElement(CloudRide.serializer(), item)
  "route" -> json.decodeFromJsonElement(CloudRoute.serializer(), item)
  "note" -> json.decodeFromJsonElement(CloudNote.serializer(), item)
  "ask" -> json.decodeFromJsonElement(CloudAsk.serializer(), item)
  "deleted" -> json.decodeFromJsonElement(CloudDeleted.serializer(), item)
  else -> null
}
