package com.touringproject.v2.api

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
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
  private val http = OkHttpClient()

  private fun client(timeoutMs: Long = 15_000) =
    ApiClient(server.url("/Prod/").toString(), "key-1", "rider01", http, timeoutMs = timeoutMs, retryDelay = { delays += it })

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

  @Test
  fun clients_made_from_one_http_client_share_its_connections() = runTest {
    repeat(2) { server.enqueue(json(200, """{"askId":"a1","status":"processing"}""")) }
    client().getAsk("a1")
    client().getAsk("a1")
    server.takeRequest()
    // The second request is the second one on the first request's connection.
    assertEquals(1, server.takeRequest().sequenceNumber)
  }

  @Test
  fun postAsk_sends_the_ride_id_only_when_there_is_one() = runTest {
    repeat(2) { server.enqueue(json(202, """{"askId":"a1","conversationId":"c1","conversationExpiresAt":"2026-01-01T00:15:00Z"}""")) }
    client().postAsk(AskBody(text = "q", rideId = "r1"))
    client().postAsk(AskBody(text = "q"))
    assertEquals("r1", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["rideId"]!!.jsonPrimitive.content)
    assertFalse("rideId" in Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject)
  }

  @Test
  fun getAsk_reads_when_where_and_which_way() = runTest {
    server.enqueue(json(200, """{"askId":"a1","status":"completed","audio":[],"timings":{},"askedAt":"2026-10-18T01:02:03.456Z","address":"静岡県御殿場市","headingDegrees":92.5}"""))
    val result = client().getAsk("a1")
    assertEquals("2026-10-18T01:02:03.456Z", result.askedAt)
    assertEquals("静岡県御殿場市", result.address)
    assertEquals(92.5, result.headingDegrees!!, 0.0)
  }

  @Test
  fun putRide_sends_the_whole_ride_with_its_open_segment_as_null() = runTest {
    server.enqueue(json(200, """{"rideId":"r1","applied":true}"""))
    val body = RideBody("箱根", "recording", listOf(SegmentBody("2026-10-18T01:00:00Z", null)), false, "2026-10-18T01:00:00Z")
    client().putRide("r1", body)
    val request = server.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("/Prod/rides/r1", request.path)
    assertEquals("rider01", request.getHeader("x-user-name"))
    val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    assertEquals("recording", sent["status"]!!.jsonPrimitive.content)
    assertEquals(JsonNull, sent["segments"]!!.jsonArray.single().jsonObject["endedAt"])
  }

  @Test
  fun putRoute_sends_the_chunk_under_its_number() = runTest {
    server.enqueue(json(200, """{"rideId":"r1","seq":3}"""))
    client().putRoute("r1", 3, RouteBody(listOf(PointBody("2026-10-18T01:00:05Z", 35.0, 139.0, null, 0))))
    val request = server.takeRequest()
    assertEquals("/Prod/rides/r1/route/3", request.path)
    val point = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["points"]!!.jsonArray.single().jsonObject
    assertEquals(JsonNull, point["accuracy"])
    assertEquals(0, point["segment"]!!.jsonPrimitive.content.toInt())
  }

  @Test
  fun a_put_is_retried_when_the_server_fails_but_not_when_it_rejects_the_input() = runTest {
    server.enqueue(json(503))
    server.enqueue(json(200, """{"rideId":"r1","seq":0}"""))
    client().putRoute("r1", 0, RouteBody(listOf(PointBody("2026-10-18T01:00:05Z", 35.0, 139.0, 5.0, 0))))
    assertEquals(2, server.requestCount)
    server.enqueue(json(400, """{"error":"bad_request","message":"segments must not overlap"}"""))
    val error = runCatching {
      client().putRide("r1", RideBody("箱根", "paused", listOf(SegmentBody("2026-10-18T01:00:00Z", "2026-10-18T02:00:00Z")), false, "2026-10-18T02:00:00Z"))
    }.exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.BAD_REQUEST, error.kind)
    assertEquals(3, server.requestCount)
  }

  // Android #26: the log of a refused send says why the Backend refused it.
  @Test
  fun a_refusal_carries_the_backends_message() = runTest {
    server.enqueue(json(400, """{"error":"bad_request","message":"segments must not overlap"}"""))
    val error = runCatching { client().putRoute("r1", 0, RouteBody(emptyList())) }.exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.BAD_REQUEST, error.kind)
    assertEquals("HTTP 400: segments must not overlap", error.message)
    server.enqueue(json(400, "not json"))
    val plain = runCatching { client().putRoute("r1", 0, RouteBody(emptyList())) }.exceptionOrNull() as ApiError
    assertEquals("HTTP 400", plain.message)
  }

  // Slice 3 spec §5.4: a send to what was deleted is answered 410.
  @Test
  fun a_send_to_a_deleted_record_is_gone_and_not_retried() = runTest {
    server.enqueue(json(410, """{"error":"deleted"}"""))
    val error = runCatching { client().putAskMemo("a1", AskMemoBody("x", "2026-10-21T01:00:00Z")) }.exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.GONE, error.kind)
    assertEquals(1, server.requestCount)
  }

  @Test
  fun putRide_sends_the_memo_and_leaves_it_out_when_there_is_none() = runTest {
    server.enqueue(json(200, """{"rideId":"r1","applied":true}"""))
    server.enqueue(json(200, """{"rideId":"r1","applied":true}"""))
    val segments = listOf(SegmentBody("2026-10-18T01:00:00Z", "2026-10-18T02:00:00Z"))
    client().putRide("r1", RideBody("箱根", "ended", segments, false, "2026-10-18T02:00:00Z", memo = "また来たい"))
    client().putRide("r1", RideBody("箱根", "ended", segments, false, "2026-10-18T02:00:01Z"))
    assertEquals("また来たい", Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["memo"]!!.jsonPrimitive.content)
    // The Backend takes a ride without memo as one with none, and drops the memo it had.
    assertFalse("memo" in Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject)
  }

  @Test
  fun putAskMemo_sends_the_memo_or_null_to_remove_it() = runTest {
    server.enqueue(json(200, """{"askId":"a1","applied":true}"""))
    client().putAskMemo("a1", AskMemoBody(null, "2026-10-21T01:00:00Z"))
    val request = server.takeRequest()
    assertEquals("PUT", request.method)
    assertEquals("/Prod/asks/a1/memo", request.path)
    val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    assertEquals(JsonNull, sent["memo"])
    assertEquals("2026-10-21T01:00:00Z", sent["updatedAt"]!!.jsonPrimitive.content)
  }

  // A deletion is answered 204 with no body, and is the same however often it is sent.
  @Test
  fun deletions_go_to_their_record_and_are_retried_when_the_server_fails() = runTest {
    server.enqueue(MockResponse().setResponseCode(204))
    server.enqueue(json(503))
    server.enqueue(MockResponse().setResponseCode(204))
    client().deleteAsk("a1")
    client().deleteRide("r1")
    assertEquals("DELETE /Prod/asks/a1", server.takeRequest().let { "${it.method} ${it.path}" })
    assertEquals("DELETE /Prod/rides/r1", server.takeRequest().let { "${it.method} ${it.path}" })
    assertEquals(3, server.requestCount)
  }

  @Test
  fun putNote_sends_the_whole_memo_and_reads_the_address_and_heading() = runTest {
    server.enqueue(json(200, """{"noteId":"n1","applied":true,"address":"静岡県御殿場市新橋","headingDegrees":45.5}"""))
    val body = NoteBody(
      at = "2026-10-22T01:24:00Z",
      location = Point(35.3, 138.9),
      headingSample = HeadingSample(35.29, 138.89, "2026-10-22T01:23:40Z"),
      text = "展望スペース",
      updatedAt = "2026-10-22T01:25:00Z",
    )
    assertEquals(NoteSaved("静岡県御殿場市新橋", 45.5), client().putNote("r1", "n1", body))
    val request = server.takeRequest()
    assertEquals("PUT /Prod/rides/r1/notes/n1", "${request.method} ${request.path}")
    val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    assertEquals("展望スペース", sent["text"]!!.jsonPrimitive.content)
    assertEquals("35.3", sent["location"]!!.jsonObject["latitude"]!!.jsonPrimitive.content)
    assertEquals("2026-10-22T01:23:40Z", sent["headingSample"]!!.jsonObject["at"]!!.jsonPrimitive.content)
    assertEquals("2026-10-22T01:24:00Z", sent["at"]!!.jsonPrimitive.content)
  }

  // A memo opened before the ride had a point has no place; the Backend then has no address for it.
  @Test
  fun a_memo_without_a_place_sends_it_as_null_and_gets_no_address() = runTest {
    server.enqueue(json(200, """{"noteId":"n1","applied":false,"address":null,"headingDegrees":null}"""))
    assertEquals(NoteSaved(null, null), client().putNote("r1", "n1", NoteBody("2026-10-22T01:24:00Z", null, null, "x", "2026-10-22T01:25:00Z")))
    val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    assertEquals(JsonNull, sent["location"])
    assertEquals(JsonNull, sent["headingSample"])
  }

  @Test
  fun deleteNote_goes_to_the_memo_under_its_ride() = runTest {
    server.enqueue(MockResponse().setResponseCode(204))
    client().deleteNote("r1", "n1")
    assertEquals("DELETE /Prod/rides/r1/notes/n1", server.takeRequest().let { "${it.method} ${it.path}" })
  }

  // Slice 3 spec §6.2: the voice of a point memo goes to be turned into text only. Like an ask, a lost response may
  // already have made one, so only 429 is sent again.
  @Test
  fun postTranscription_sends_the_audio_and_tries_again_only_on_a_rate_limit() = runTest {
    server.enqueue(json(503))
    val error = runCatching { client().postTranscription(TranscriptionBody("AAAA")) }.exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.SERVER, error.kind)
    assertEquals(1, server.requestCount)
    server.takeRequest()
    server.enqueue(json(429))
    server.enqueue(json(202, """{"transcriptionId":"t1"}"""))
    assertEquals(TranscriptionAccepted("t1"), client().postTranscription(TranscriptionBody("AAAA")))
    val request = server.takeRequest()
    assertEquals("POST /Prod/transcriptions", "${request.method} ${request.path}")
    assertEquals("AAAA", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["audio"]!!.jsonPrimitive.content)
  }

  @Test
  fun getTranscription_reads_what_was_heard_or_why_not() = runTest {
    server.enqueue(json(200, """{"transcriptionId":"t1","status":"done","text":"展望スペース","failure":null}"""))
    server.enqueue(json(200, """{"transcriptionId":"t2","status":"failed","text":null,"failure":"no_speech"}"""))
    assertEquals(TranscriptionResult("t1", "done", "展望スペース", null), client().getTranscription("t1"))
    assertEquals(TranscriptionResult("t2", "failed", null, "no_speech"), client().getTranscription("t2"))
    assertEquals("GET /Prod/transcriptions/t1", server.takeRequest().let { "${it.method} ${it.path}" })
  }

  // Slice 4 spec §4.4: an unreached ask goes whole under the device's id, and the Backend answers its place and heading.
  @Test
  fun putOfflineAsk_sends_the_unreached_ask_and_reads_the_address_and_heading() = runTest {
    server.enqueue(json(200, """{"askId":"u1","applied":true,"address":"神奈川県箱根町仙石原","headingDegrees":12.5}"""))
    val body = OfflineAskBody("2026-10-22T01:24:00Z", null, null, "r1", "2026-10-22T01:24:00Z")
    assertEquals(OfflineAskSaved("神奈川県箱根町仙石原", 12.5), client().putOfflineAsk("u1", body))
    val request = server.takeRequest()
    assertEquals("PUT /Prod/asks/u1/offline", "${request.method} ${request.path}")
    val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
    assertEquals("r1", sent["rideId"]!!.jsonPrimitive.content)
    assertEquals(JsonNull, sent["location"])
    assertEquals(JsonNull, sent["headingSample"])
  }

  // 409: the id is an ask the Backend took in; it is not retried.
  @Test
  fun an_unreached_ask_over_a_taken_one_is_a_conflict_and_not_retried() = runTest {
    server.enqueue(json(409, """{"error":"conflict"}"""))
    val error = runCatching { client().putOfflineAsk("u1", OfflineAskBody("2026-10-22T01:24:00Z", null, null, "r1", "2026-10-22T01:24:00Z")) }
      .exceptionOrNull() as ApiError
    assertEquals(ApiErrorKind.CONFLICT, error.kind)
    assertEquals(1, server.requestCount)
  }

  // Slice 4 spec §6.2: every kind of record, values the Backend has not got as null, and whole numbers as numbers.
  @Test
  fun records_reads_each_kind_of_record() = runTest {
    server.enqueue(
      json(
        200,
        """{"items":[
          {"kind":"ride","rideId":"r1","name":"箱根","status":"ended","segments":[{"startedAt":"2026-10-22T01:00:00Z","endedAt":"2026-10-22T02:00:00Z"}],"autoEnded":false,"updatedAt":"2026-10-22T02:00:00.123Z","memo":null},
          {"kind":"route","rideId":"r1","seq":0,"points":[{"t":"2026-10-22T01:00:05Z","lat":35,"lon":139.5,"accuracy":null,"segment":0}]},
          {"kind":"note","rideId":"r1","noteId":"n1","at":"2026-10-22T01:10:00Z","location":{"latitude":35.1,"longitude":139},"text":"展望","updatedAt":"2026-10-22T01:10:00Z","address":null,"headingDegrees":90},
          {"kind":"ask","askId":"a1","status":"completed","askedAt":"2026-10-22T01:20:00Z","question":null,"transcript":"右手の山は","answer":"金時山です","error":null,"answeredAt":"2026-10-22T01:20:12Z","location":null,"address":"箱根町仙石原","headingDegrees":12.5,"inputType":"audio","conversationId":"c1","newConversation":true,"rideId":"r1","memo":null,"memoUpdatedAt":null,"updatedAt":null},
          {"kind":"deleted","target":"note","id":"n2","rideId":"r1","deletedAt":"2026-10-22T03:00:00Z"}
        ],"cursor":null}""",
      ),
    )
    val page = client().records(null)
    assertEquals(null, page.cursor)
    assertEquals(
      listOf(
        CloudRide("r1", "箱根", "ended", listOf(SegmentBody("2026-10-22T01:00:00Z", "2026-10-22T02:00:00Z")), false, "2026-10-22T02:00:00.123Z", null),
        CloudRoute("r1", 0, listOf(PointBody("2026-10-22T01:00:05Z", 35.0, 139.5, null, 0))),
        CloudNote("r1", "n1", "2026-10-22T01:10:00Z", Point(35.1, 139.0), "展望", "2026-10-22T01:10:00Z", null, 90.0),
        CloudAsk(
          "a1", "completed", "2026-10-22T01:20:00Z", null, "右手の山は", "金時山です", null, "2026-10-22T01:20:12Z", null, "箱根町仙石原", 12.5,
          "audio", "c1", true, "r1", null, null, null,
        ),
        CloudDeleted("note", "n2", "r1", "2026-10-22T03:00:00Z"),
      ),
      page.items,
    )
    val request = server.takeRequest()
    assertEquals("GET /Prod/records", "${request.method} ${request.path}")
    assertEquals("rider01", request.getHeader("x-user-name"))
  }

  // The cursor is opaque: it goes back as it came, whatever characters it has.
  @Test
  fun records_sends_the_cursor_back_and_tells_there_is_more() = runTest {
    server.enqueue(json(200, """{"items":[],"cursor":"eyJw+IjoiIn0="}"""))
    server.enqueue(json(200, """{"items":[],"cursor":null}"""))
    val first = client().records(null)
    assertEquals("eyJw+IjoiIn0=", first.cursor)
    client().records(first.cursor)
    server.takeRequest()
    assertEquals("/Prod/records?cursor=eyJw%2BIjoiIn0%3D", server.takeRequest().path)
  }

  // A kind the device does not know yet is left out; a record of a known kind it cannot read fails the whole page.
  @Test
  fun records_leaves_out_unknown_kinds_and_fails_on_a_broken_record() = runTest {
    server.enqueue(json(200, """{"items":[{"kind":"transcription","id":"t1"}],"cursor":null}"""))
    server.enqueue(json(200, """{"items":[{"kind":"ride","rideId":"r1"}],"cursor":null}"""))
    assertEquals(emptyList<CloudRecord>(), client().records(null).items)
    assertTrue(runCatching { client().records(null) }.exceptionOrNull() is kotlinx.serialization.SerializationException)
  }

  @Test
  fun records_retries_a_server_failure() = runTest {
    server.enqueue(json(503))
    server.enqueue(json(200, """{"items":[],"cursor":null}"""))
    assertEquals(CloudPage(emptyList(), null), client().records(null))
    assertEquals(2, server.requestCount)
  }
}
