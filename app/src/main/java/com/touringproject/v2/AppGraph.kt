package com.touringproject.v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings as SystemSettings
import android.util.Log
import com.touringproject.v2.api.ApiClient
import com.touringproject.v2.ask.AskController
import com.touringproject.v2.ask.AskDeps
import com.touringproject.v2.ask.ConversationState
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.IDLE
import com.touringproject.v2.ask.Source
import com.touringproject.v2.ask.isBusy
import com.touringproject.v2.ask.rideNow
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
import com.touringproject.v2.location.FusedRouteSource
import com.touringproject.v2.location.PositionTracker
import com.touringproject.v2.location.RouteSource
import com.touringproject.v2.nav.AppLauncher
import com.touringproject.v2.net.Connectivity
import com.touringproject.v2.recordings.RecordingCheck
import com.touringproject.v2.recordings.RecordingKeeper
import com.touringproject.v2.recordings.RecordingStore
import com.touringproject.v2.recordings.TRIAL_IDLE
import com.touringproject.v2.recordings.TrialController
import com.touringproject.v2.recordings.TrialDeps
import com.touringproject.v2.recordings.TrialState
import com.touringproject.v2.records.RideListItem
import com.touringproject.v2.records.activeAskCount
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RideController
import com.touringproject.v2.ride.RideDeps
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.service.AlarmDeadline
import com.touringproject.v2.service.ContextServiceLauncher
import com.touringproject.v2.service.RideNotices
import com.touringproject.v2.service.ServiceCoordinator
import com.touringproject.v2.settings.ApiKeyStore
import com.touringproject.v2.settings.DEFAULT_MAX_RECORDING_HOURS
import com.touringproject.v2.settings.DataStoreKeyValueStore
import com.touringproject.v2.settings.KeystoreCipher
import com.touringproject.v2.settings.SettingsRepository
import com.touringproject.v2.settings.maxRecordingChanges
import com.touringproject.v2.store.RideDatabase
import com.touringproject.v2.store.RoomAskEdits
import com.touringproject.v2.store.RoomAskLog
import com.touringproject.v2.store.RoomNotes
import com.touringproject.v2.store.RoomRecords
import com.touringproject.v2.store.RoomRideStore
import com.touringproject.v2.store.RoomSyncStore
import com.touringproject.v2.sync.RideSync
import com.touringproject.v2.sync.credentialChanges
import com.touringproject.v2.voice.VOICE_IDLE
import com.touringproject.v2.voice.VoiceDeps
import com.touringproject.v2.voice.VoiceInput
import com.touringproject.v2.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/** Builds the app's parts by hand (no DI library) and owns them for the life of the process. */
class AppGraph(private val context: Context, val scope: CoroutineScope = MainScope()) {
  val apiBaseUrl: String = BuildConfig.API_BASE_URL
  /** The one HTTP client of the app: every ApiClient shares its connections and threads (Android #12). */
  val http = OkHttpClient()
  private val store = DataStoreKeyValueStore(context)
  val settings = SettingsRepository(store)
  val apiKeys = ApiKeyStore(store, KeystoreCipher())
  val launcher = AppLauncher(context)
  val audioRoute = AudioRoute(context)
  val conversation = ConversationState()
  val recordingStore = RecordingStore(File(context.filesDir, "recordings"))
  private val cues = ToneCues()
  private val micAllowed = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

  val serviceCoordinator = ServiceCoordinator(ContextServiceLauncher(context)) { message, e -> Log.w("trv2.ride", message, e) }
  val routeSource: RouteSource = FusedRouteSource(context)
  private val database = RideDatabase.open(context)
  val rideStore = RoomRideStore(database.rides())
  val askLog = RoomAskLog(database.asks(), scope) { message, e -> Log.w("trv2.ask", message, e) }
  /** What the Records tab reads (SC-05, SC-06, SC-07). */
  val records = RoomRecords(database.rides(), database.asks(), database.notes())

  /**
   * SC-05's list, shared so that coming back from a ride shows the list it left at once (Android #49). It is read only
   * while the list is shown and for 5 s after: Room runs the query again for every route point (Android #42).
   */
  val recordsList: StateFlow<List<RideListItem>?> = records.list.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
  private val notices = RideNotices(context)

  /** Sends rides, routes, memos, point memos and deletions; a pass runs on each trigger (slice 2 spec §5.3). */
  val sync = RideSync(
    RoomSyncStore(database.rides(), database.asks(), database.notes()),
    api = {
      val key = apiKeys.current()
      val user = settings.settings.value?.userName
      if (apiBaseUrl.isEmpty() || key == null || user == null) null else ApiClient(apiBaseUrl, key, user, http)
    },
    log = { event, data -> Log.i("trv2.sync", "$event $data") },
  )

  /** SC-07 and SC-08 change kept asks through here; each change is something new to send (slice 3 spec §5.1). */
  val askEdits = RoomAskEdits(database.asks(), System::currentTimeMillis, sync::request) { message, e -> Log.w("trv2.ask", message, e) }

  /** SC-19 writes point memos through here; each change is something new to send (slice 3 spec §5.1). */
  val notes = RoomNotes(database.notes(), database.rides(), System::currentTimeMillis, { UUID.randomUUID().toString() }, sync::request) { message, e ->
    Log.w("trv2.note", message, e)
  }

  val rides = RideController(
    RideDeps(
      now = System::currentTimeMillis,
      bootCount = { runCatching { SystemSettings.Global.getInt(context.contentResolver, SystemSettings.Global.BOOT_COUNT) }.getOrNull() },
      newId = { UUID.randomUUID().toString() },
      // Read at each start and resume; before the settings are loaded only the default exists.
      maxRecordingMs = { (settings.settings.value?.maxRecordingHours ?: DEFAULT_MAX_RECORDING_HOURS) * HOUR_MS },
      store = rideStore,
      alarm = AlarmDeadline(context),
      recording = serviceCoordinator::setRecording,
      autoEnded = notices::autoEnded,
      changed = sync::request,
      pointAdded = sync::pointAdded,
      log = { event, data -> Log.i("trv2.ride", "$event $data") },
    ),
  )

  /** How many asks the device keeps for the ride recording or paused (SC-02, SC-04; Android #35). */
  val activeAskCount: StateFlow<Int> =
    activeAskCount(rides.active.map { it?.id }, database.asks()::countForRide).stateIn(scope, SharingStarted.Eagerly, 0)

  private val flowState = MutableStateFlow(IDLE)
  val state: StateFlow<FlowState> = flowState.asStateFlow()

  private var permissionPromptTaken = false

  val controller: AskController = AskController(
    AskDeps(
      now = System::currentTimeMillis,
      sleep = { delay(it) },
      settings = settings::current,
      apiKey = { if (apiBaseUrl.isEmpty()) null else apiKeys.current() },
      micAllowed = micAllowed,
      mediaVolume = { runCatching { audioRoute.mediaVolume() }.getOrNull() },
      api = { key, user -> ApiClient(apiBaseUrl, key, user, http) },
      conversation = conversation,
      route = AudioRouteControl(audioRoute),
      recorder = PcmRecorder(),
      recordings = RecordingKeeper(recordingStore, scope) { message, e -> Log.w("trv2.recordings", message, e) },
      asks = askLog,
      online = { connectivity.online.value },
      newId = { UUID.randomUUID().toString() },
      ride = { rideNow(rides.active.value) },
      location = PositionTracker(FusedPositionSource(context)),
      service = serviceCoordinator.keepAlive,
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

  /** Whether the device has a network now (SC-19's voice input). */
  val connectivity = Connectivity(context)

  private val voiceFlow = MutableStateFlow(VOICE_IDLE)
  val voiceState: StateFlow<VoiceState> = voiceFlow.asStateFlow()

  // SC-19's voice input: its own route control and recorder, like SC-12's trial. The audio route underneath is the
  // app's one, so the press router keeps asks off it while a voice input unwinds.
  val voice = VoiceInput(
    VoiceDeps(
      now = System::currentTimeMillis,
      sleep = { delay(it) },
      micAllowed = micAllowed,
      online = { connectivity.online.value },
      api = {
        val key = apiKeys.current()
        val user = settings.settings.value?.userName
        if (apiBaseUrl.isEmpty() || key == null || user == null) null else ApiClient(apiBaseUrl, key, user, http)
      },
      route = AudioRouteControl(audioRoute),
      recorder = PcmRecorder(),
      cueStart = cues::start,
      cueFailure = cues::failure,
      onState = { voiceFlow.value = it },
      log = { event, data -> Log.i("trv2.note", "$event $data") },
    ),
    scope,
  )

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
    askBusy = { isBusy(controller.state) },
    voiceAttached = { voice.attached },
    voicePress = { voice.press(Source.HANDSFREE) },
    voiceRecording = { voice.recording },
    voiceStop = voice::stop,
    voiceBusy = { voice.busy },
  )

  /** The screen's "押して話す", routed like an intercom press so it waits for a left trial to unwind. */
  fun tapToTalk() = router.tap()

  /** The intercom button presses wait here until the app is ready ([readiness]). */
  val presses = PressGate { router.press() }

  private val readiness = Readiness { presses.openGate(System.currentTimeMillis()) }
  val isReady: StateFlow<Boolean> = readiness.isReady

  init {
    audioRoute.onVoiceRecognitionEnded = { router.recognitionEnded() }
    // Settles a ride left recording or paused before this process started (slice 2 spec §4.5).
    // load() finishes even when the read fails, so the app never stays on "準備中…" because of it.
    scope.launch {
      rides.load()
      readiness.ridesLoaded()
    }
    scope.launch { sync.run() }
    // SC-16: the recording ride's deadline follows the longest recording time, wherever it was changed (Android #33).
    scope.launch { maxRecordingChanges(settings.settings).collect { rides.maxRecordingChanged() } }
    scope.launch {
      settings.load()
      apiKeys.load()
      readiness.settingsLoaded()
      // The start of the app is a trigger; before this the key and the user name are not known.
      sync.request()
      // From now on, a key or user name saved may let what waits on the device go at last (Android #29).
      credentialChanges(apiKeys.apiKey, settings.settings.map { it?.userName }).collect { sync.request() }
    }
  }

  /**
   * True once per process: the first activity of this process asks for permissions, whether it starts fresh or is
   * restored after process death (Android #38). An activity recreated in the same process does not ask again.
   */
  fun takePermissionPrompt(): Boolean {
    if (permissionPromptTaken) return false
    permissionPromptTaken = true
    return true
  }

  fun permissionsAnswered() = readiness.permissionsAnswered()
}
