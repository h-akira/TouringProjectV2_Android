package com.touringproject.v2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import com.touringproject.v2.theme.TouringV2Theme

// Placeholder until the app is wired up (Task 10 replaces it).
class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { TouringV2Theme { Text("Touring V2") } }
  }
}
