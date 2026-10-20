package com.touringproject.v2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app opens (the screen and the intercom presses) once the settings are loaded, the ride left from before this
 * process has been read, and the permission prompt has been answered. [open] runs once, when the last of them arrives.
 * Waiting for the ride keeps a restored SC-04 sheet from closing itself on a first draw with no ride (Android #38).
 */
class Readiness(private val open: () -> Unit) {
  private val ready = MutableStateFlow(false)
  val isReady: StateFlow<Boolean> = ready.asStateFlow()
  private var settingsLoaded = false
  private var ridesLoaded = false
  private var permissionsAnswered = false

  fun settingsLoaded() {
    settingsLoaded = true
    openWhenReady()
  }

  fun ridesLoaded() {
    ridesLoaded = true
    openWhenReady()
  }

  fun permissionsAnswered() {
    permissionsAnswered = true
    openWhenReady()
  }

  private fun openWhenReady() {
    if (!settingsLoaded || !ridesLoaded || !permissionsAnswered || ready.value) return
    ready.value = true
    open()
  }
}
