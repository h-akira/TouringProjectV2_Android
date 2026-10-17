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
import com.touringproject.v2.ask.isBusy
import com.touringproject.v2.audio.AudioRoute
import com.touringproject.v2.audio.AudioRouteControl
import com.touringproject.v2.audio.ExoAnswerPlayer
import com.touringproject.v2.audio.PcmPlayer
import com.touringproject.v2.audio.PcmRecorder
import com.touringproject.v2.audio.ToneCues
import com.touringproject.v2.audio.TtsSpeech
import com.touringproject.v2.handsfree.PressGate
import com.touringproject.v2.handsfree.PressRouter
import com.touringproject.v2.location.FusedPositionSource
import com.touringproject.v2.location.PositionTracker
import com.touringproject.v2.nav.AppLauncher
import com.touringproject.v2.recordings.RecordingCheck
import com.touringproject.v2.recordings.RecordingKeeper
import com.touringproject.v2.recordings.RecordingStore
import com.touringproject.v2.recordings.TRIAL_IDLE
import com.touringproject.v2.recordings.TrialController
import com.touringproject.v2.recordings.TrialDeps
import com.touringproject.v2.recordings.TrialState
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
import java.io.File

/** Builds the app's parts by hand (no DI library) and owns them for the life of the process. */
class AppGraph(private val context: Context, val scope: CoroutineScope = MainScope()) {
  val apiBaseUrl: String = BuildConfig.API_BASE_URL
  private val store = DataStoreKeyValueStore(context)
  val settings = SettingsRepository(store)
  val apiKeys = ApiKeyStore(store, KeystoreCipher())
  val launcher = AppLauncher(context)
  val audioRoute = AudioRoute(context)
  val conversation = ConversationState()
  val recordingStore = RecordingStore(File(context.filesDir, "recordings"))
  private val cues = ToneCues()
  private val micAllowed = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

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
      micAllowed = micAllowed,
      mediaVolume = { runCatching { audioRoute.mediaVolume() }.getOrNull() },
      api = { key, user -> ApiClient(apiBaseUrl, key, user) },
      conversation = conversation,
      route = AudioRouteControl(audioRoute),
      recorder = PcmRecorder(),
      recordings = RecordingKeeper(recordingStore, scope) { message, e -> Log.w("trv2.recordings", message, e) },
      location = PositionTracker(FusedPositionSource(context)),
      service = ServiceKeepAlive(context),
      nav = launcher,
      cues = cues,
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

  private val trialFlow = MutableStateFlow(TRIAL_IDLE)
  val trialState: StateFlow<TrialState> = trialFlow.asStateFlow()

  // SC-12: its own route control, recorder and players, so nothing it does is shared with the ask loop.
  val trial = TrialController(
    TrialDeps(
      now = System::currentTimeMillis,
      sleep = { delay(it) },
      micAllowed = micAllowed,
      route = AudioRouteControl(audioRoute),
      recorder = PcmRecorder(),
      cueStart = cues::start,
      player = PcmPlayer(),
      onState = { trialFlow.value = it },
    ),
  )

  val recordingCheck = RecordingCheck(recordingStore, trial, PcmPlayer(), { isBusy(controller.state) }, scope) { message, e ->
    Log.w("trv2.recordings", message, e)
  }

  // The media volume at which the rider closed the low-volume band; kept until the process ends (slice 1 spec §5.6).
  private val volumeDismissed = MutableStateFlow<Int?>(null)
  val volumeDismissedAt: StateFlow<Int?> = volumeDismissed.asStateFlow()

  fun dismissVolumeWarning(level: Int) {
    volumeDismissed.value = level
  }

  private val router = PressRouter(
    trialActive = { trial.active },
    trialBusy = { trial.busy },
    stopTrial = trial::stop,
    stopCheckPlayback = recordingCheck::stopPlaying,
    askPress = controller::press,
    askStopRecording = controller::stopRecording,
    askTap = controller::tapToTalk,
  )

  /** The screen's "押して話す", routed like an intercom press so it waits for a left trial to unwind. */
  fun tapToTalk() = router.tap()

  /** The intercom button presses wait here until settings are loaded and the permission prompt has been answered. */
  val presses = PressGate { router.press() }

  init {
    audioRoute.onVoiceRecognitionEnded = { router.recognitionEnded() }
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
