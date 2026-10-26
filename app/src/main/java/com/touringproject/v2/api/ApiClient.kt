package com.touringproject.v2.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

// Backend API (docs-parent/07a_openapi.yaml). Errors are classified so the ask loop can pick the spoken message.
// GONE is 410: the record, or its ride, was deleted (slice 3 spec §5.4); the sync treats it as sent. CONFLICT is 409: an
// unreached ask's id is one the Backend took in (slice 4 spec §4.4); the sync treats it as refused.
enum class ApiErrorKind { INVALID_KEY, BAD_REQUEST, NOT_FOUND, GONE, CONFLICT, RATE_LIMITED, SERVER, NETWORK }

class ApiError(val kind: ApiErrorKind, message: String? = null) : Exception(message ?: kind.name)

@Serializable data class Point(val latitude: Double, val longitude: Double)

/**
 * The older point the Backend once worked a heading out from. Only point memos and unreached asks kept before
 * slice 5 still have one; whatever the device makes now goes with headingDegrees (slice 5 spec §4.4).
 */
@Serializable data class HeadingSample(val latitude: Double, val longitude: Double, val at: String)

@Serializable
data class AskBody(
  val audio: String? = null,
  val text: String? = null,
  val location: Point? = null,
  /** Worked out on the device from the ride's route (slice 5 spec §4); null when there is none. */
  val headingDegrees: Double? = null,
  val conversationId: String? = null,
  val volumeDb: Int? = null,
  /** The recording ride the ask belongs to; every ask has one since asks need a recording ride (slice 2 spec §4.7). */
  val rideId: String? = null,
)

@Serializable data class AskAccepted(val askId: String, val conversationId: String, val conversationExpiresAt: String)

@Serializable data class AudioClip(val seq: Int, val url: String)

@Serializable
data class AskResult(
  val askId: String,
  val status: String,
  val transcript: String? = null,
  val answer: String? = null,
  val audio: List<AudioClip> = emptyList(),
  val error: String? = null,
  /** When the Backend took the ask in (UTC). */
  val askedAt: String? = null,
  val address: String? = null,
  val headingDegrees: Double? = null,
)

interface AskApi {
  suspend fun postAsk(body: AskBody): AskAccepted
  suspend fun getAsk(askId: String): AskResult
}

/** A point memo's voice, to be turned into text only (slice 3 spec §6.2): 16 kHz mono 16-bit PCM in base64, 0.25 to 60 s. */
@Serializable data class TranscriptionBody(val audio: String)

@Serializable data class TranscriptionAccepted(val transcriptionId: String)

/** status: processing / done / failed. text is set when done; failure (no_speech / transcribe_failed) when failed. */
@Serializable
data class TranscriptionResult(val transcriptionId: String, val status: String, val text: String? = null, val failure: String? = null)

interface TranscriptionApi {
  suspend fun postTranscription(body: TranscriptionBody): TranscriptionAccepted
  suspend fun getTranscription(transcriptionId: String): TranscriptionResult
}

// A ride and its route (slice 2 spec §6.2). Times are ISO 8601 with their time zone.
@Serializable data class SegmentBody(val startedAt: String, val endedAt: String?)

/** memo goes with the ride as a whole: a ride without one (memo left out) drops the memo the Backend has (slice 3 spec §6.2). */
@Serializable
data class RideBody(
  val name: String,
  val status: String,
  val segments: List<SegmentBody>,
  val autoEnded: Boolean,
  val updatedAt: String,
  val memo: String? = null,
)

@Serializable data class PointBody(val t: String, val lat: Double, val lon: Double, val accuracy: Double?, val segment: Int)

@Serializable data class RouteBody(val points: List<PointBody>)

/** A conversation's memo (slice 3 spec §6.2); null removes it. */
@Serializable data class AskMemoBody(val memo: String?, val updatedAt: String)

/**
 * A point memo as a whole (slice 3 spec §6.2). location is null for one opened before its ride had a point.
 * headingDegrees and headingSample are as an unreached ask's (OfflineAskBody).
 */
@Serializable
data class NoteBody(
  val at: String,
  val location: Point?,
  val headingSample: HeadingSample?,
  val text: String,
  val updatedAt: String,
  val headingDegrees: Double? = null,
)

/** What the Backend settled for a point memo when it first took it; null when it could not tell (no place, no heading). */
@Serializable data class NoteSaved(val address: String? = null, val headingDegrees: Double? = null)

/**
 * An ask that never reached the Backend, as a whole (slice 4 spec §4.4). location is null when it had no place yet.
 * headingDegrees is the device's heading; headingSample is left only on one kept before slice 5 (spec §4.4).
 */
@Serializable
data class OfflineAskBody(
  val askedAt: String,
  val location: Point?,
  val headingSample: HeadingSample?,
  val rideId: String,
  val updatedAt: String,
  val headingDegrees: Double? = null,
)

/** What the Backend settled for an unreached ask when it first took it; null when it could not tell. */
@Serializable data class OfflineAskSaved(val address: String? = null, val headingDegrees: Double? = null)

/**
 * Every call may be sent again and again: the Backend keeps one copy of each, and a deletion is the same however often
 * it comes. A 400 throws BAD_REQUEST, a 404 NOT_FOUND, a 409 CONFLICT and a 410 (deleted) GONE.
 */
interface RideApi {
  suspend fun putRide(rideId: String, body: RideBody)
  suspend fun putRoute(rideId: String, seq: Int, body: RouteBody)
  suspend fun putAskMemo(askId: String, body: AskMemoBody)
  suspend fun putNote(rideId: String, noteId: String, body: NoteBody): NoteSaved
  suspend fun putOfflineAsk(askId: String, body: OfflineAskBody): OfflineAskSaved
  suspend fun deleteRide(rideId: String)
  suspend fun deleteAsk(askId: String)
  suspend fun deleteNote(rideId: String, noteId: String)
}

private val TRANSIENT = setOf(ApiErrorKind.RATE_LIMITED, ApiErrorKind.SERVER, ApiErrorKind.NETWORK)

// Any UUID works: a valid key gets 404 for an unknown ask, an invalid key gets 403.
private const val PROBE_ASK_ID = "00000000-0000-4000-8000-000000000000"

private fun kindFor(status: Int): ApiErrorKind? = when {
  status == 403 -> ApiErrorKind.INVALID_KEY
  status == 400 -> ApiErrorKind.BAD_REQUEST
  status == 404 -> ApiErrorKind.NOT_FOUND
  status == 409 -> ApiErrorKind.CONFLICT
  status == 410 -> ApiErrorKind.GONE
  status == 429 -> ApiErrorKind.RATE_LIMITED
  status >= 500 -> ApiErrorKind.SERVER
  else -> null
}

/** A refusal (400) carries the Backend's message, so the sync's log tells why it is not sent again (Android #26). */
private fun reason(code: Int, kind: ApiErrorKind, body: String?): String {
  val message = if (kind == ApiErrorKind.BAD_REQUEST && body != null) {
    runCatching { Json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
  } else {
    null
  }
  return message?.let { "HTTP $code: $it" } ?: "HTTP $code"
}

/** http is the app's one OkHttpClient, so every client shares its connections and threads (Android #12). */
class ApiClient(
  baseUrl: String,
  private val apiKey: String,
  private val userName: String,
  http: OkHttpClient,
  private val maxAttempts: Int = 3,
  timeoutMs: Long = 15_000,
  private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) : AskApi, RideApi, TranscriptionApi, RecordsApi {
  private val baseUrl = baseUrl.trimEnd('/')
  // callTimeout bounds the whole exchange, so a stalled connection cannot hang the ask.
  private val http = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
  private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
  // The ride's open segment and a point without an accuracy are sent as null, as the contract writes them.
  private val rideJson = Json { explicitNulls = true }

  // Only 429 is retried for POST: a lost response may already have created an ask.
  override suspend fun postAsk(body: AskBody): AskAccepted =
    json.decodeFromString(request("POST", "/asks", json.encodeToString(AskBody.serializer(), body), setOf(ApiErrorKind.RATE_LIMITED)))

  override suspend fun getAsk(askId: String): AskResult = json.decodeFromString(request("GET", "/asks/$askId", null, TRANSIENT))

  // Like postAsk: a lost response may already have made a transcription, so only 429 is sent again.
  override suspend fun postTranscription(body: TranscriptionBody): TranscriptionAccepted = json.decodeFromString(
    request("POST", "/transcriptions", json.encodeToString(TranscriptionBody.serializer(), body), setOf(ApiErrorKind.RATE_LIMITED)),
  )

  override suspend fun getTranscription(transcriptionId: String): TranscriptionResult =
    json.decodeFromString(request("GET", "/transcriptions/$transcriptionId", null, TRANSIENT))

  override suspend fun putRide(rideId: String, body: RideBody) {
    request("PUT", "/rides/$rideId", rideJson.encodeToString(RideBody.serializer(), body), TRANSIENT)
  }

  override suspend fun putRoute(rideId: String, seq: Int, body: RouteBody) {
    request("PUT", "/rides/$rideId/route/$seq", rideJson.encodeToString(RouteBody.serializer(), body), TRANSIENT)
  }

  override suspend fun putAskMemo(askId: String, body: AskMemoBody) {
    request("PUT", "/asks/$askId/memo", rideJson.encodeToString(AskMemoBody.serializer(), body), TRANSIENT)
  }

  override suspend fun putNote(rideId: String, noteId: String, body: NoteBody): NoteSaved =
    json.decodeFromString(NoteSaved.serializer(), request("PUT", "/rides/$rideId/notes/$noteId", rideJson.encodeToString(NoteBody.serializer(), body), TRANSIENT))

  override suspend fun putOfflineAsk(askId: String, body: OfflineAskBody): OfflineAskSaved = json.decodeFromString(
    OfflineAskSaved.serializer(),
    request("PUT", "/asks/$askId/offline", rideJson.encodeToString(OfflineAskBody.serializer(), body), TRANSIENT),
  )

  override suspend fun deleteRide(rideId: String) {
    request("DELETE", "/rides/$rideId", null, TRANSIENT)
  }

  override suspend fun deleteAsk(askId: String) {
    request("DELETE", "/asks/$askId", null, TRANSIENT)
  }

  override suspend fun deleteNote(rideId: String, noteId: String) {
    request("DELETE", "/rides/$rideId/notes/$noteId", null, TRANSIENT)
  }

  override suspend fun records(cursor: String?): CloudPage {
    val query = cursor?.let { "?cursor=" + URLEncoder.encode(it, "UTF-8") } ?: ""
    val page = json.decodeFromString(RecordsPageBody.serializer(), request("GET", "/records$query", null, TRANSIENT))
    return CloudPage(page.items.mapNotNull { cloudRecord(json, it) }, page.cursor)
  }

  suspend fun checkKey() {
    try {
      request("GET", "/asks/$PROBE_ASK_ID", null, TRANSIENT)
    } catch (e: ApiError) {
      if (e.kind != ApiErrorKind.NOT_FOUND) throw e
    }
  }

  private suspend fun request(method: String, path: String, body: String?, retryOn: Set<ApiErrorKind>): String {
    var attempt = 1
    while (true) {
      try {
        return once(method, path, body)
      } catch (e: ApiError) {
        if (e.kind !in retryOn || attempt >= maxAttempts) throw e
        retryDelay(500L * attempt)
        attempt += 1
      }
    }
  }

  // Cancelling the coroutine cancels the HTTP call, so the poll limit can cut off a request that is still waiting.
  private suspend fun once(method: String, path: String, body: String?): String = suspendCancellableCoroutine { cont ->
    val request = Request.Builder()
      .url("$baseUrl$path")
      .header("x-api-key", apiKey)
      .header("x-user-name", userName)
      .method(method, body?.toRequestBody("application/json".toMediaType()))
      .build()
    val call = http.newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        cont.resumeWithException(ApiError(ApiErrorKind.NETWORK, e.toString()))
      }

      override fun onResponse(call: Call, response: Response) {
        val outcome = runCatching {
          response.use {
            kindFor(it.code)?.let { kind -> throw ApiError(kind, reason(it.code, kind, it.body?.string())) }
            it.body?.string() ?: throw ApiError(ApiErrorKind.NETWORK, "empty body")
          }
        }.recoverCatching { e -> throw if (e is IOException) ApiError(ApiErrorKind.NETWORK, e.toString()) else e }
        cont.resumeWith(outcome)
      }
    })
  }
}
