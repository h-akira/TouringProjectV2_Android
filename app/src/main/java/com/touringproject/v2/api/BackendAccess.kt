package com.touringproject.v2.api

import okhttp3.OkHttpClient

/**
 * The one place a Backend client is made: from the API's URL, the connection key and a user name (07a). Without the URL
 * or the key (or, for the current user, the name) there is none, and each caller tells the rider as it did before.
 */
class BackendAccess(
  private val baseUrl: String,
  private val http: OkHttpClient,
  private val key: () -> String?,
  private val userName: () -> String?,
) {
  /** The API's URL is not set (SC-13 says so). */
  val urlMissing: Boolean get() = baseUrl.isEmpty()

  /** The connection key, or null when there is none or no URL (the ask loop tells NO_KEY then). */
  fun apiKey(): String? = if (baseUrl.isEmpty()) null else key()

  /** A client for the current user name, or null when the URL, the key or the name is missing. */
  fun client(): ApiClient? = client(userName())

  /** A client for the given user name (SC-18 reads another name's records), or null without the URL or the key. */
  fun client(user: String?): ApiClient? {
    val key = key()
    return if (baseUrl.isEmpty() || key == null || user == null) null else ApiClient(baseUrl, key, user, http)
  }

  /** A client for a key that is given: the ask loop's, which has checked it, and SC-13's, typed and not yet saved. */
  fun clientFor(key: String, user: String): ApiClient = ApiClient(baseUrl, key, user, http)
}
