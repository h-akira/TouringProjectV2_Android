package com.touringproject.v2.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the device's default network reaches the internet, followed for the life of the process (slice 3 spec §7.3:
 * SC-19 cannot send a voice without one; slice 4 spec §4.1 and §5.2: the ask loop and the sync). It says nothing of
 * whether the Backend answers; a send can still fail.
 */
class Connectivity(context: Context) {
  private val manager = context.getSystemService(ConnectivityManager::class.java)
  private val state = MutableStateFlow(manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }.reaches())
  val online: StateFlow<Boolean> = state.asStateFlow()
  // The default network the last report was about. Losing another one (the old network, reported late after a switch
  // from Wi-Fi to mobile data) leaves the state alone (Android #62).
  @Volatile private var current: Network? = manager.activeNetwork
  private val comebackCount = MutableStateFlow(0)

  /**
   * How many times the network has come back (from none to one) in this process. Counted where the system reports it,
   * so a drop too short for a collector of online to see still counts (Android #69).
   */
  val comebacks: StateFlow<Int> = comebackCount.asStateFlow()

  init {
    manager.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
          current = network
          report(capabilities.reaches())
        }

        override fun onLost(network: Network) {
          if (current != null && network != current) return
          current = null
          report(false)
        }
      },
    )
  }

  @Synchronized
  private fun report(reaches: Boolean) {
    if (reaches && !state.value) comebackCount.value += 1
    state.value = reaches
  }
}

private fun NetworkCapabilities?.reaches() = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
