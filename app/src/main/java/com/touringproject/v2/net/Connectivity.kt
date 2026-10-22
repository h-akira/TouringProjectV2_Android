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
 * SC-19 cannot send a voice without one). It says nothing of whether the Backend answers; a send can still fail.
 */
class Connectivity(context: Context) {
  private val manager = context.getSystemService(ConnectivityManager::class.java)
  private val state = MutableStateFlow(manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }.reaches())
  val online: StateFlow<Boolean> = state.asStateFlow()

  init {
    manager.registerDefaultNetworkCallback(
      object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
          state.value = capabilities.reaches()
        }

        override fun onLost(network: Network) {
          state.value = false
        }
      },
    )
  }
}

private fun NetworkCapabilities?.reaches() = this?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
