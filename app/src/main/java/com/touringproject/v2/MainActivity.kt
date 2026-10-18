package com.touringproject.v2

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.touringproject.v2.theme.TouringV2Theme
import com.touringproject.v2.ui.AppUi

class MainActivity : ComponentActivity() {
  private val graph get() = (application as TouringApp).graph

  private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    graph.permissionsAnswered()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleVoiceCommand(intent, restored = savedInstanceState != null)
    if (savedInstanceState == null) {
      permissions.launch(
        arrayOf(
          Manifest.permission.RECORD_AUDIO,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_COARSE_LOCATION,
          Manifest.permission.BLUETOOTH_CONNECT,
          Manifest.permission.POST_NOTIFICATIONS,
        ),
      )
    }
    enableEdgeToEdge()
    setContent { TouringV2Theme { AppUi(graph) } }
  }

  // A ride's service could not start while the app was in the background (slice 2 spec §4.5); it can now. Coming to
  // the front is also a trigger to send what is left (slice 2 spec §5.3); with nothing left, nothing is sent.
  override fun onStart() {
    super.onStart()
    graph.serviceCoordinator.refresh()
    graph.sync.request()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleVoiceCommand(intent, restored = false)
  }

  // The intercom's assistant button. The action is rewritten so a recreated activity does not count the same press again.
  // A restored activity (after process death) and a task reopened from Recents carry the original intent: not a new press.
  private fun handleVoiceCommand(intent: Intent?, restored: Boolean) {
    if (intent?.action != Intent.ACTION_VOICE_COMMAND) return
    intent.action = Intent.ACTION_MAIN
    if (restored || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
    val now = System.currentTimeMillis()
    graph.presses.offer(now, now)
  }
}
