package com.touringproject.v2.service

import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.RouteSource
import com.touringproject.v2.location.Subscription
import kotlinx.coroutines.Job

/**
 * What the service runs while a ride records: the route watch and the minute's heartbeat. start() is called again on
 * every change of the service's types and starts only what is not running: a watch that could not start (e.g. the
 * location permission was taken away) is tried again, but the heartbeat never runs twice (Android #16).
 */
class RecordingWatch(
  private val source: RouteSource,
  private val onFix: (Fix) -> Unit,
  private val startHeartbeat: () -> Job,
  private val log: (String, Throwable) -> Unit,
) {
  private var route: Subscription? = null
  private var heartbeat: Job? = null

  fun start() {
    if (route == null) {
      route = runCatching { source.watch(onFix) }.onFailure { log("route watch failed", it) }.getOrNull()
    }
    if (heartbeat == null) heartbeat = startHeartbeat()
  }

  fun stop() {
    route?.remove()
    route = null
    heartbeat?.cancel()
    heartbeat = null
  }
}
