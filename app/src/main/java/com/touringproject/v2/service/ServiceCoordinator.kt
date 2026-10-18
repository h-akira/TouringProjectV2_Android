package com.touringproject.v2.service

import com.touringproject.v2.ask.KeepAlive

// One foreground service serves both the ride and the ask; what it is used for decides its types (slice 2 spec §4.2).

enum class ServiceUse { LOCATION, MICROPHONE, MEDIA_PLAYBACK }

fun serviceUses(recording: Boolean, asking: Boolean): Set<ServiceUse> = buildSet {
  if (recording) add(ServiceUse.LOCATION)
  if (asking) {
    add(ServiceUse.MICROPHONE)
    add(ServiceUse.MEDIA_PLAYBACK)
  }
}

/** Starts and stops the service from outside it. */
interface ServiceLauncher {
  /** Throws when the system does not let the app start a foreground service now (e.g. from the background). */
  fun start()
  fun stop()
}

/** The running service. */
interface RunningService {
  /** Changes the service's types, or stops it when uses is empty. Throws when the system refuses a type. */
  fun apply(uses: Set<ServiceUse>)
}

/**
 * Keeps the service in line with whether a ride is recording and whether an ask is under way. Call it from the
 * main thread only. The ask's KeepAlive throws when its types cannot be added, so the ask fails instead of going
 * on without the service; the ride goes on either way.
 */
class ServiceCoordinator(private val launcher: ServiceLauncher, private val log: (String, Throwable) -> Unit) {
  private var recording = false
  private var asking = false
  /** The running service, if any (read by the emulator tests). */
  var running: RunningService? = null
    private set

  val uses: Set<ServiceUse> get() = serviceUses(recording, asking)

  fun attach(service: RunningService) {
    running = service
  }

  fun detach(service: RunningService) {
    if (running === service) running = null
  }

  fun setRecording(on: Boolean) {
    recording = on
    applyQuietly()
  }

  /** Brings the service back when it should run but does not (e.g. it could not start while the app was in the background). */
  fun refresh() {
    if (uses.isNotEmpty() && running == null) applyQuietly()
  }

  val keepAlive: KeepAlive = object : KeepAlive {
    override fun start() {
      asking = true
      try {
        apply()
      } catch (e: Exception) {
        asking = false
        applyQuietly()
        throw e
      }
    }

    override fun stop() {
      asking = false
      applyQuietly()
    }
  }

  private fun apply() {
    val wanted = uses
    val service = running
    when {
      wanted.isEmpty() -> if (service != null) service.apply(wanted) else launcher.stop()
      service != null -> service.apply(wanted)
      else -> launcher.start()
    }
  }

  private fun applyQuietly() {
    try {
      apply()
    } catch (e: Exception) {
      log("service_apply_failed uses=$uses", e)
    }
  }
}
