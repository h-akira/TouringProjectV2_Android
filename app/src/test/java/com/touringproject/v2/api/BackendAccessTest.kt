package com.touringproject.v2.api

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

// The one place a Backend client is made (slice 6 plan 2a): every caller gets none, in the same cases.
class BackendAccessTest {
  private val http = OkHttpClient()

  private fun access(url: String = "https://example.test/Prod/", key: String? = "key-1", user: String? = "rider01") =
    BackendAccess(url, http, { key }, { user })

  @Test
  fun a_client_for_the_current_user_needs_the_url_the_key_and_the_name() {
    assertNotNull(access().client())
    assertNull(access(url = "").client())
    assertNull(access(key = null).client())
    assertNull(access(user = null).client())
  }

  @Test
  fun a_client_for_another_user_needs_only_the_url_and_the_key() {
    assertNotNull(access(user = null).client("rider02"))
    assertNull(access(url = "").client("rider02"))
    assertNull(access(key = null).client("rider02"))
    assertNull(access().client(null))
  }

  @Test
  fun the_key_is_none_without_the_url() {
    assertEquals("key-1", access().apiKey())
    assertNull(access(url = "").apiKey())
    assertNull(access(key = null).apiKey())
  }

  @Test
  fun a_typed_key_is_tried_without_being_saved() {
    assertNotNull(access(key = null, user = null).clientFor("typed-key", "rider01"))
  }

  @Test
  fun the_url_is_missing_when_it_is_empty() {
    assertEquals(true, access(url = "").urlMissing)
    assertEquals(false, access().urlMissing)
  }
}
