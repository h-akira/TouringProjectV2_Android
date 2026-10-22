package com.touringproject.v2.net

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

// Slice 3 spec §7.3: SC-19 follows whether the device has a network.
@RunWith(AndroidJUnit4::class)
class ConnectivityTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()
  private val manager = app.getSystemService(ConnectivityManager::class.java)

  @Test
  fun the_state_follows_the_default_network() {
    // The app's own graph has registered one already; the new one is this test's.
    val before = shadowOf(manager).networkCallbacks.toSet()
    val connectivity = Connectivity(app)
    val callback = (shadowOf(manager).networkCallbacks - before).single()
    val network = manager.activeNetwork!!
    callback.onLost(network)
    assertFalse(connectivity.online.value)
    val caps = ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
    callback.onCapabilitiesChanged(network, caps)
    assertTrue(connectivity.online.value)
    callback.onCapabilitiesChanged(network, ShadowNetworkCapabilities.newInstance())
    assertFalse(connectivity.online.value)
  }

  // Android #62: after a switch (Wi-Fi to mobile data), the old network's loss reported late does not leave it offline.
  @Test
  fun losing_the_old_network_after_a_switch_leaves_it_online() {
    val before = shadowOf(manager).networkCallbacks.toSet()
    val connectivity = Connectivity(app)
    val callback = (shadowOf(manager).networkCallbacks - before).single()
    val old = manager.activeNetwork!!
    val new = ShadowNetwork.newInstance(1_234)
    val caps = ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
    callback.onCapabilitiesChanged(new, caps)
    callback.onLost(old)
    assertTrue(connectivity.online.value)
    callback.onLost(new)
    assertFalse(connectivity.online.value)
  }
}
