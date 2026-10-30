package com.touringproject.v2

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.util.Log
import com.touringproject.v2.api.BackendAccess
import com.touringproject.v2.ask.AskController
import com.touringproject.v2.ask.AskDeps
import com.touringproject.v2.ask.ConversationState
import com.touringproject.v2.ask.FlowState
import com.touringproject.v2.ask.IDLE
import com.touringproject.v2.ask.TextSheet
import com.touringproject.v2.audio.Source
import com.touringproject.v2.ask.isBusy
import com.touringproject.v2.ask.rideNow
import com.touringproject.v2.audio.AudioRoute
import com.touringproject.v2.audio.AudioRouteControl
import com.touringproject.v2.audio.ExoAnswerPlayer
import com.touringproject.v2.audio.MediaVolumeWatch
import com.touringproject.v2.audio.PcmPlayer
import com.touringproject.v2.audio.PcmRecorder
import com.touringproject.v2.audio.RouteLeases
import com.touringproject.v2.audio.ToneCues
import com.touringproject.v2.audio.TtsSpeech
import com.touringproject.v2.handsfree.PressGate
import com.touringproject.v2.handsfree.PressRouter
import com.touringproject.v2.location.FusedPositionSource
import com.touringproject.v2.location.FusedRouteSource
import com.touringproject.v2.location.GpsClock
import com.touringproject.v2.location.HeadingCheck
import com.touringproject.v2.location.HeadingParams
import com.touringproject.v2.location.PositionSource
import com.touringproject.v2.location.PositionTracker
import com.touringproject.v2.location.RouteSource
import com.touringproject.v2.location.degrees
import com.touringproject.v2.location.seenBy
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
import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.records.activeAskCount
import com.touringproject.v2.records.whileShown
import com.touringproject.v2.ride.HOUR_MS
import com.touringproject.v2.ride.RideController
import com.touringproject.v2.ride.RideDeps
import com.touringproject.v2.ride.RideStatus
import com.touringproject.v2.ride.RouteHeading
import com.touringproject.v2.service.AlarmDeadline
import com.touringproject.v2.service.ContextServiceLauncher
import com.touringproject.v2.service.RideNotices
import com.touringproject.v2.service.ServiceCoordinator
import com.touringproject.v2.settings.ApiKeyStore
import com.touringproject.v2.settings.DEFAULT_MAX_RECORDING_HOURS
import com.touringproject.v2.settings.DEFAULT_START_CUE_MS
import com.touringproject.v2.settings.DataStoreKeyValueStore
import com.touringproject.v2.settings.KeystoreCipher
import com.touringproject.v2.settings.LastSaved
import com.touringproject.v2.settings.SettingsRepository
import com.touringproject.v2.settings.headingParams
import com.touringproject.v2.settings.recordLimitMs
import com.touringproject.v2.settings.maxRecordingChanges
import com.touringproject.v2.store.RideDatabase
import com.touringproject.v2.store.RoomAskEdits
import com.touringproject.v2.store.RoomAskLog
import com.touringproject.v2.store.RoomNotes
import com.touringproject.v2.store.RoomOwner
import com.touringproject.v2.store.RoomRecords
import com.touringproject.v2.store.RoomRideStore
import com.touringproject.v2.store.RoomSyncStore
import com.touringproject.v2.store.RoomTakeover
import com.touringproject.v2.store.toPoint
import com.touringproject.v2.sync.RecordOwner
import com.touringproject.v2.sync.RideSync
import com.touringproject.v2.sync.Takeover
import com.touringproject.v2.sync.credentialChanges
import com.touringproject.v2.sync.reconnections
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
  private val http = OkHttpClient()
  private val store = DataStoreKeyValueStore(context)
  val settings = SettingsRepository(store)
  val apiKeys = ApiKeyStore(store, KeystoreCipher())
  /** The one place a Backend client is made, from the URL, the connection key and the user name (slice 6 plan 2a). */
  val backend = BackendAccess(apiBaseUrl, http, apiKeys::current) { settings.settings.value?.userName }
  val launcher = AppLauncher(context)
  val audioRoute = AudioRoute(context)
  // The one audio route, handed to the ask loop, SC-12's trial and SC-19's voice input one at a time.
  private val routes = RouteLeases(AudioRouteControl(audioRoute))
  val conversation = ConversationState()
  val recordingStore = RecordingStore(File(context.filesDir, "recordings"))
  private val cues = ToneCues()
  private val micAllowed = { context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }

  val serviceCoordinator = ServiceCoordinator(ContextServiceLauncher(context)) { message, e -> Log.w("trv2.ride", message, e) }
  /** The present on the fixes' own time, learnt from every fix the app gets (Android #17). */
  val gpsClock = GpsClock(SystemClock::elapsedRealtime, System::currentTimeMillis)
  val routeSource: RouteSource = FusedRouteSource(context).seenBy(gpsClock)
  /** The 1 s positions of an ask's recording and of SC-22. */
  val positionSource: PositionSource = FusedPositionSource(context).seenBy(gpsClock).also { gpsClock.learnFrom(it) }
  private val database = RideDatabase.open(context)
  val rideStore = RoomRideStore(database.rides())
  // sync is made below; it is read only when an unreached ask has been written.
  val askLog = RoomAskLog(database.asks(), scope, unreachedKept = { sync.request() }) { message, e -> Log.w("trv2.ask", message, e) }
  /** What the Records tab reads (SC-05, SC-06, SC-07). */
  val records = RoomRecords(database.rides(), database.asks(), database.notes())

  /**
   * SC-05's list, shared so that coming back from a ride shows the list it left at once (Android #49). It is read only
   * while the list is shown and for 5 s after: Room runs the query again for every route point (Android #42).
   */
  val recordsList: StateFlow<List<RideListItem>?> = records.list.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)
  private val notices = RideNotices(context)

  /** Whether the device has a network now: SC-19's voice input, the ask loop and the sync follow it. */
  val connectivity = Connectivity(context)

  /** When a pass of the sync last ran to its end (SC-14). */
  val lastSaved = LastSaved(store)

  /** Whose records the device keeps; a pass of the sync goes only when the name of the settings is the owner's (adr/0015). */
  val owner = RecordOwner(
    store = RoomOwner(database.takeover()),
    name = { settings.settings.value?.userName },
    confirmed = { settings.nameConfirmed },
    log = { message, e -> Log.w("trv2.sync", message, e) },
  )

  /** Sends rides, routes, memos, point memos, unreached asks and deletions; a pass runs on each trigger (slice 2 spec §5.3). */
  val sync = RideSync(
    RoomSyncStore(database.rides(), database.asks(), database.notes()),
    api = { backend.client() },
    online = { connectivity.online.value },
    saved = { lastSaved.mark(System.currentTimeMillis()) },
    mayPass = owner::check,
    log = { event, data -> Log.i("trv2.sync", "$event $data") },
  )

  /** SC-14 and SC-18: what is left to send, and how much is kept (slice 4 spec §7.1). */
  val saveSummary: StateFlow<SaveSummary?> = records.summary.whileShown(scope)

  /** Reads the user's records in from the cloud: SC-18's change of user name and SC-14's read again (slice 4 spec §6). */
  val takeover = Takeover(
    api = { user -> backend.client(user) },
    // Read at the take, long after rides below is built.
    store = RoomTakeover(database) { rides.waitingToSave.value },
    // A made-up name is not made the owner by a read again (adr/0015).
    nameConfirmed = { settings.nameConfirmed },
    sync = sync,
    now = System::currentTimeMillis,
    scope = scope,
    log = { event, data -> Log.i("trv2.sync", "$event $data") },
  )

  /** SC-07 and SC-08 change kept asks through here; each change is something new to send (slice 3 spec §5.1). */
  val askEdits = RoomAskEdits(database.asks(), System::currentTimeMillis, sync::request) { message, e -> Log.w("trv2.ask", message, e) }

  val rides = RideController(
    RideDeps(
      now = System::currentTimeMillis,
      elapsed = SystemClock::elapsedRealtime,
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

  /** The heading from the ride's route, with the parameters the rider chose (slice 5 spec §4). */
  val routeHeading = RouteHeading(
    ride = { rides.active.value },
    points = { rideId, segment, count -> database.rides().segmentTail(rideId, segment, count).map { it.toPoint() } },
    params = { settings.settings.value?.headingParams ?: HeadingParams() },
    now = gpsClock::now,
    log = { message, e -> Log.w("trv2.ride", message, e) },
  )

  /**
   * SC-22's heading, a new one each time the screen shows (slice 5 spec §4.5): the ride's route while it records, and
   * points gathered the same way otherwise. A test puts one on fake sources here, to count the watches (Android #85).
   */
  var headingCheck: () -> HeadingCheck = {
    HeadingCheck(
      positionSource,
      routeSource,
      recording = { rides.active.value?.status == RideStatus.RECORDING },
      rideHeading = routeHeading::find,
      params = { settings.settings.value?.headingParams ?: HeadingParams() },
      gpsNow = gpsClock::now,
    )
  }

  /** SC-19 writes point memos through here; each change is something new to send (slice 3 spec §5.1). */
  val notes = RoomNotes(
    database.notes(), database.rides(), System::currentTimeMillis, { UUID.randomUUID().toString() }, sync::request,
    heading = { here -> routeHeading.find(here).degrees },
  ) { message, e -> Log.w("trv2.note", message, e) }

  /** How many asks the device keeps for the ride recording or paused (SC-02, SC-04; Android #35). */
  val activeAskCount: StateFlow<Int> =
    activeAskCount(rides.active.map { it?.id }, database.asks()::countForRide).stateIn(scope, SharingStarted.Eagerly, 0)

  /** The media volume for Home's band and SC-03's (slice 1 spec §5.6). */
  val mediaVolume = MediaVolumeWatch { audioRoute.mediaVolume() }

  /**
   * Home's text sheet (slice 5 spec §5.2). Held for the process, not the activity: a text ask's outcome may come after the
   * activity was recreated (rotated while SC-03 shows), and it must close the sheet that is shown then.
   */
  val textSheet = MutableStateFlow(TextSheet())

  private val flowState = MutableStateFlow(IDLE)
  val state: StateFlow<FlowState> = flowState.asStateFlow()

  private var permissionPromptTaken = false

  val controller: AskController = AskController(
    AskDeps(
      now = System::currentTimeMillis,
      sleep = { delay(it) },
      settings = settings::current,
      apiKey = backend::apiKey,
      micAllowed = micAllowed,
      // Read before the intercom route takes the music stream over; a withheld reading keeps the last good one (Android #9).
      mediaVolume = mediaVolume::refresh,
      api = backend::clientFor,
      conversation = conversation,
      route = routes,
      recorder = PcmRecorder(),
      recordings = RecordingKeeper(recordingStore, scope) { message, e -> Log.w("trv2.recordings", message, e) },
      asks = askLog,
      online = { connectivity.online.value },
      newId = { UUID.randomUUID().toString() },
      ride = { rideNow(rides.active.value) },
      location = PositionTracker(positionSource),
      heading = { here -> routeHeading.find(here).degrees },
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

  // SC-12: its own recorder and players; the audio route is the app's one, taken one user at a time.
  val trial = TrialController(
    TrialDeps(
      now = System::currentTimeMillis,
      sleep = { delay(it) },
      micAllowed = micAllowed,
      route = routes,
      recorder = PcmRecorder(),
      cueStart = cues::start,
      player = PcmPlayer(),
      onState = { trialFlow.value = it },
      // SC-11's values, as a question takes them (US-E-03.001).
      recordLimitMs = { settings.current().recordLimitMs },
      startCueMs = { settings.current().startCueMs },
    ),
  )

  val recordingCheck = RecordingCheck(recordingStore, trial, PcmPlayer(), { isBusy(controller.state) }, scope) { message, e ->
    Log.w("trv2.recordings", message, e)
  }

  private val voiceFlow = MutableStateFlow(VOICE_IDLE)
  val voiceState: StateFlow<VoiceState> = voiceFlow.asStateFlow()

  // SC-19's voice input: its own recorder, like SC-12's trial. The audio route is the app's one, taken one user at a time.
  val voice = VoiceInput(
    VoiceDeps(
      now = System::currentTimeMillis,
      sleep = { delay(it) },
      micAllowed = micAllowed,
      online = { connectivity.online.value },
      api = { backend.client() },
      route = routes,
      recorder = PcmRecorder(),
      // Not a question: SC-11 does not change SC-19's voice input (slice 5 spec §6).
      cueStart = { cues.start(DEFAULT_START_CUE_MS) },
      cueFailure = cues::failure,
      onState = { voiceFlow.value = it },
      log = { event, data -> Log.i("trv2.note", "$event $data") },
    ),
    scope,
  )


  private val router = PressRouter(
    trialActive = { trial.active },
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
    // The network coming back lets what waited for it go, while the process lives (slice 4 spec §5.2).
    scope.launch { reconnections(connectivity.comebacks).collect { sync.request() } }
    // SC-16: the recording ride's deadline follows the longest recording time, wherever it was changed (Android #33).
    scope.launch { maxRecordingChanges(settings.settings).collect { rides.maxRecordingChanged() } }
    scope.launch {
      settings.load()
      apiKeys.load()
      lastSaved.load()
      readiness.settingsLoaded()
      // The start of the app is a trigger; before this the key and the user name are not known.
      sync.request()
      // From now on, a key or user name saved may let what waits on the device go at last (Android #29).
      credentialChanges(apiKeys.apiKey, settings.settings.map { it?.userName }).collect { sync.request() }
    }
  }

  /** A change of user name saves the name before the records come in, and throws when it cannot (Android #72). */
  suspend fun saveUserName(userName: String) {
    settings.save(settings.current().copy(userName = userName))
  }

  /**
   * A change of user name has read the new name's records in (slice 4 spec §6.4): the conversation ends, since the
   * Agent keeps one per user name. SC-12's recordings are the device's and stay.
   */
  fun switched() {
    conversation.reset()
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
