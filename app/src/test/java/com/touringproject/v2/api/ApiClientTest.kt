package com.touringproject.v2.api

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiClientTest {
  private val server = MockWebServer().apply { start() }
  private val delays = mutableListOf<Long>()

  private fun client(timeoutMs: Long = 15_000) =
    ApiClient(server.url("/Prod/").toString(), "key-1", "rider01", timeoutMs = timeoutMs, retryDelay = { delays += it })

  private fun json(code: Int, body: String = "{}") = MockResponse().setResponseCode(code).setBody(body)

  @After
  fun tearDown() = server.shutdown()

  @Test
  fun postAsk_sends_headers_and_json() = runTest {
    server.enqueue(json(202, """{"askId":"a1","conversationId":"c1","conversationExpiresAt":"2026-01-01T00:15:00Z"}"""))
    val accepted = client().postAsk(AskBody(audio = "UENN", location = Point(35.0, 139.0), volumeDb = 6))
    assertEquals(AskAccepted("a1", "c1", "2026-01-01T00:15:00Z"), accepted)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/Prod/asks", request.path)
    assertEquals("key-1", request.getHeader("x-api-key"))
    assertEquals("rider01", request.getHeader("x-user-name"))
    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    assertEquals("UENN", body["audio"]!!.jsonPrimitive.content)
    assertEquals(6, body["volumeDb"]!!.jsonPrimitive.content.toInt())
    // Absent fields are left out rather than sent as null.
    assertFalse("conversationId" in body)
  }

  @Test
  fun getAsk_reads_the_result_and_ignores_unknown_fields() = runTest {
    server.enqueue(json(200, """{"askId":"a1","status":"completed","transcript":"Q","answer":"A","audio":[{"seq":1,"url":"u"}],"error":null,"timings":{"accepted":"t"}}"""))
    assertEquals(AskResult("a1", "completed", "Q", "A", listOf(AudioClip(1, "u")), null), client().getAsk("a1"))
  }

  @Test
  fun get_retries_transient_errors_then_succeeds() = runTest {
    server.enqueue(json(503))
    server.enqueue(json(429))
    server.enqueue(json(200, """{"askId":"a1","status":"processing"}"""))
    assertEquals("processing", client().getAsk("a1").status)
    assertEquals(listOf(500L, 1_000L), delays)
  }

  @Test
  fun post_does_not_retry_network_errors_but_retries_429() = runTest {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
    val error = runCatching { client().postAsk(AskBody(text = "q")) }.exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.NETWORK, error.kind)
    assertEquals(1, server.requestCount)
    server.enqueue(json(429))
    server.enqueue(json(202, """{"askId":"a2","conversationId":"c1","conversationExpiresAt":"2026-01-01T00:15:00Z"}"""))
    assertEquals("a2", client().postAsk(AskBody(text = "q")).askId)
  }

  @Test
  fun invalid_key_is_not_retried() = runTest {
    server.enqueue(json(403))
    val error = runCatching { client().getAsk("a1") }.exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.INVALID_KEY, error.kind)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun checkKey_treats_404_as_a_working_key() = runTest {
    server.enqueue(json(404))
    client().checkKey()
    server.enqueue(json(403))
    assertEquals(ApiErrorKind.INVALID_KEY, (runCatching { client().checkKey() }.exceptionOrNull() as ApiError).kind)
  }

  @Test
  fun a_stalled_request_times_out_as_a_network_error() = runTest {
    repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
    val error = runCatching { client(timeoutMs = 200).getAsk("a1") }.exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.NETWORK, error.kind)
  }

  @Test
  fun cancelling_a_waiting_request_cancels_the_call(): Unit = runBlocking {
    server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
    val startedAt = System.nanoTime()
    // The client would wait 15 s for this response; the poll limit cancels it much sooner.
    assertNull(withTimeoutOrNull(300) { client().getAsk("a1") })
    assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 5_000)
  }
}
