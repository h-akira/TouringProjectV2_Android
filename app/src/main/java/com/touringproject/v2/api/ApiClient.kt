package com.touringproject.v2.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

// Backend API (Backend/openapi.yaml). Errors are classified so the ask loop can pick the spoken message.
enum class ApiErrorKind { INVALID_KEY, BAD_REQUEST, NOT_FOUND, RATE_LIMITED, SERVER, NETWORK }

class ApiError(val kind: ApiErrorKind, message: String? = null) : Exception(message ?: kind.name)

@Serializable data class Point(val latitude: Double, val longitude: Double)

@Serializable data class HeadingSample(val latitude: Double, val longitude: Double, val at: String)

@Serializable
data class AskBody(
  val audio: String? = null,
  val text: String? = null,
  val location: Point? = null,
  val headingSample: HeadingSample? = null,
  val conversationId: String? = null,
  val volumeDb: Int? = null,
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
)

interface AskApi {
  suspend fun postAsk(body: AskBody): AskAccepted
  suspend fun getAsk(askId: String): AskResult
}

private val TRANSIENT = setOf(ApiErrorKind.RATE_LIMITED, ApiErrorKind.SERVER, ApiErrorKind.NETWORK)

// Any UUID works: a valid key gets 404 for an unknown ask, an invalid key gets 403.
private const val PROBE_ASK_ID = "00000000-0000-4000-8000-000000000000"

private fun kindFor(status: Int): ApiErrorKind? = when {
  status == 403 -> ApiErrorKind.INVALID_KEY
  status == 400 -> ApiErrorKind.BAD_REQUEST
  status == 404 -> ApiErrorKind.NOT_FOUND
  status == 429 -> ApiErrorKind.RATE_LIMITED
  status >= 500 -> ApiErrorKind.SERVER
  else -> null
}

class ApiClient(
  baseUrl: String,
  private val apiKey: String,
  private val userName: String,
  private val maxAttempts: Int = 3,
  timeoutMs: Long = 15_000,
  private val retryDelay: suspend (Long) -> Unit = { delay(it) },
  http: OkHttpClient = OkHttpClient(),
) : AskApi {
  private val baseUrl = baseUrl.trimEnd('/')
  // callTimeout bounds the whole exchange, so a stalled connection cannot hang the ask.
  private val http = http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
  private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

  // Only 429 is retried for POST: a lost response may already have created an ask.
  override suspend fun postAsk(body: AskBody): AskAccepted =
    json.decodeFromString(request("POST", "/asks", json.encodeToString(AskBody.serializer(), body), setOf(ApiErrorKind.RATE_LIMITED)))

  override suspend fun getAsk(askId: String): AskResult = json.decodeFromString(request("GET", "/asks/$askId", null, TRANSIENT))

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
            kindFor(it.code)?.let { kind -> throw ApiError(kind, "HTTP ${it.code}") }
            it.body?.string() ?: throw ApiError(ApiErrorKind.NETWORK, "empty body")
          }
        }.recoverCatching { e -> throw if (e is IOException) ApiError(ApiErrorKind.NETWORK, e.toString()) else e }
        cont.resumeWith(outcome)
      }
    })
  }
}
