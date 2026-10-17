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
    handleVoiceCommand(intent)
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

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleVoiceCommand(intent)
  }

  // The intercom's assistant button. The action is rewritten so a recreated activity does not count the same press again.
  private fun handleVoiceCommand(intent: Intent?) {
    if (intent?.action != Intent.ACTION_VOICE_COMMAND) return
    intent.action = Intent.ACTION_MAIN
    val now = System.currentTimeMillis()
    graph.presses.offer(now, now)
  }
}
