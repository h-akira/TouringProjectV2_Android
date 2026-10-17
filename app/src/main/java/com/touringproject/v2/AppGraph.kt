package com.touringproject.v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.touringproject.v2.api.ApiClient
import com.touringproject.v2.ask.AskController
import com.touringproject.v2.ask.AskDeps
import com.touringproject.v2.ask.ConversationState
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.IDLE
import com.touringproject.v2.ask.RecordingMeta
import com.touringproject.v2.ask.RecordingSink
import com.touringproject.v2.audio.AudioRoute
import com.touringproject.v2.audio.AudioRouteControl
import com.touringproject.v2.audio.ExoAnswerPlayer
import com.touringproject.v2.audio.PcmRecorder
import com.touringproject.v2.audio.ToneCues
import com.touringproject.v2.audio.TtsSpeech
import com.touringproject.v2.handsfree.PressGate
import com.touringproject.v2.location.FusedPositionSource
import com.touringproject.v2.location.PositionTracker
import com.touringproject.v2.nav.AppLauncher
import com.touringproject.v2.service.ServiceKeepAlive
import com.touringproject.v2.settings.ApiKeyStore
import com.touringproject.v2.settings.DataStoreKeyValueStore
import com.touringproject.v2.settings.KeystoreCipher
import com.touringproject.v2.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Builds the app's parts by hand (no DI library) and owns them for the life of the process. */
class AppGraph(private val context: Context, val scope: CoroutineScope = MainScope()) {
  val apiBaseUrl: String = BuildConfig.API_BASE_URL
  private val store = DataStoreKeyValueStore(context)
  val settings = SettingsRepository(store)
  val apiKeys = ApiKeyStore(store, KeystoreCipher())
  val launcher = AppLauncher(context)
  val audioRoute = AudioRoute(context)
  val conversation = ConversationState()

  private val flowState = MutableStateFlow(IDLE)
  val state: StateFlow<FlowState> = flowState.asStateFlow()

  private val ready = MutableStateFlow(false)
  val isReady: StateFlow<Boolean> = ready.asStateFlow()
  private var loaded = false
  private var permissionsAsked = false

  val controller: AskController = AskController(
    AskDeps(
      now = System::currentTimeMillis,
      sleep = { delay(it) },
      settings = settings::current,
      apiKey = { if (apiBaseUrl.isEmpty()) null else apiKeys.current() },
      micAllowed = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
      mediaVolume = { runCatching { audioRoute.mediaVolume() }.getOrNull() },
      api = { key, user -> ApiClient(apiBaseUrl, key, user) },
      conversation = conversation,
      route = AudioRouteControl(audioRoute),
      recorder = PcmRecorder(),
      // Recordings are kept for SC-12 from plan 2 of the migration.
      recordings = object : RecordingSink {
        override fun saved(meta: RecordingMeta, pcm: ByteArray) = Unit
        override fun transcribed(name: String, transcript: String) = Unit
      },
      location = PositionTracker(FusedPositionSource(context)),
      service = ServiceKeepAlive(context),
      nav = launcher,
      cues = ToneCues(),
      player = ExoAnswerPlayer(context),
      speech = TtsSpeech(context),
      onState = {
        Log.i("trv2.ask", "phase=${it.phase} source=${it.source} failure=${it.failure} phoneMic=${it.phoneMic}")
        flowState.value = it
      },
      log = { event, data -> Log.i("trv2.ask", "$event $data") },
    ),
    scope,
  )

  /** The intercom button presses wait here until settings are loaded and the permission prompt has been answered. */
  val presses = PressGate { controller.press() }

  init {
    // The headset dropping SCO during a voice-recognition session is the second press.
    audioRoute.onVoiceRecognitionEnded = { controller.stopRecording() }
    scope.launch {
      settings.load()
      apiKeys.load()
      loaded = true
      openWhenReady()
    }
  }

  fun permissionsAnswered() {
    permissionsAsked = true
    openWhenReady()
  }

  private fun openWhenReady() {
    if (!loaded || !permissionsAsked || ready.value) return
    ready.value = true
    presses.openGate(System.currentTimeMillis())
  }
}
