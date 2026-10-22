package com.touringproject.v2.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.touringproject.v2.AppGraph
import com.touringproject.v2.BuildConfig
import com.touringproject.v2.api.ApiClient
import com.touringproject.v2.ask.isBusy
import com.touringproject.v2.records.NoteDraft
import com.touringproject.v2.settings.LaunchableApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable data object HomeKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object ConnectionKey : NavKey
@Serializable data object UserKey : NavKey
@Serializable data object ReturnAppKey : NavKey
@Serializable data object VolumeKey : NavKey
@Serializable data object RecordingsKey : NavKey
@Serializable data object RideRecordingKey : NavKey
@Serializable data object RecordsKey : NavKey
@Serializable data class RideKey(val rideId: String) : NavKey
@Serializable data class AskKey(val rideId: String, val askId: String) : NavKey
@Serializable data class RideMemoKey(val rideId: String) : NavKey
@Serializable data class AskMemoKey(val rideId: String, val askId: String) : NavKey
/** SC-19 opened from Home; the draft says where and when, so it stays the same when the activity is recreated. */
@Serializable data class NewNoteKey(val draft: NoteDraft) : NavKey
@Serializable data class NoteKey(val rideId: String, val noteId: String) : NavKey

/** SC-08 and SC-19 cover the tabs, as the mock draws them. */
private fun coversTabs(key: NavKey?) = key is RideMemoKey || key is AskMemoKey || key is NewNoteKey || key is NoteKey

private enum class RideSheet { START, END }

/** The clock for the ride card, ticking every second while a ride is shown. */
@Composable
private fun rememberNow(ticking: Boolean): Long {
  var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(ticking) {
    while (ticking) {
      now = System.currentTimeMillis()
      delay(1_000)
    }
  }
  return now
}

@Composable
fun AppUi(graph: AppGraph) {
  val ready by graph.isReady.collectAsStateWithLifecycle()
  val state by graph.state.collectAsStateWithLifecycle()
  val settings by graph.settings.settings.collectAsStateWithLifecycle()
  val apiKey by graph.apiKeys.apiKey.collectAsStateWithLifecycle()
  val volumeDismissedAt by graph.volumeDismissedAt.collectAsStateWithLifecycle()
  val ride by graph.rides.active.collectAsStateWithLifecycle()
  val storeFailed by graph.rides.storeFailed.collectAsStateWithLifecycle()
  val askStoreFailed by graph.askLog.failed.collectAsStateWithLifecycle()
  val questions by graph.activeAskCount.collectAsStateWithLifecycle()
  // SC-06 tells while its map fills the screen; the tabs go with it (slice 2 spec §7.1).
  var mapFull by remember { mutableStateOf(false) }
  // Saveable: SC-17 and SC-04 stay open when the activity is recreated, e.g. on rotation (Android #20).
  var sheet by rememberSaveable { mutableStateOf<RideSheet?>(null) }
  val now = rememberNow(ride != null)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val homeStack = rememberNavBackStack(HomeKey)
  val recordsStack = rememberNavBackStack(RecordsKey)
  val settingsStack = rememberNavBackStack(SettingsKey)
  var selectedTab by rememberSaveable { mutableStateOf(Tab.HOME) }
  val noteVoice = remember(graph) { NoteVoice(graph.voice, graph.voiceState, graph.connectivity.online) }
  val tabs = remember(homeStack, recordsStack, settingsStack) {
    TabStacks(mapOf(Tab.HOME to homeStack, Tab.RECORDS to recordsStack, Tab.SETTINGS to settingsStack), { selectedTab }, { selectedTab = it })
  }
  val current = settings
  // Re-read on every phase change, so the label updates once an ask has renewed the conversation.
  val expiresAt = remember(state) { graph.conversation.expiresAt(System.currentTimeMillis()) }
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize()) {
      if (!ready || current == null) {
        Text("準備中…", Modifier.safeDrawingPadding())
      } else {
        // NavDisplay handles Back within a stack; on the first screen of Records or Settings, Back goes to Home. On
        // Home's first screen nothing takes it, and the system moves the app to the back (Android #32).
        BackHandler(enabled = tabs.onAnotherTabsFirst) { tabs.back() }
        Scaffold(
          containerColor = MaterialTheme.colorScheme.background,
          bottomBar = { if (tabsShown(state.phase) && !mapFull && !coversTabs(tabs.current.lastOrNull())) TabBar(tabs.tab, tabs::press) },
        ) { inner ->
          val screens = entryProvider<NavKey> {
              entry<HomeKey> {
                val volume = rememberMediaVolume(volumeReadable(state.phase)) { graph.audioRoute.mediaVolume() }
                val ui = HomeUi(
                  userName = current.userName,
                  banner = homeBanner(
                    hasKey = apiKey != null,
                    returnAppMissing = current.returnEnabled && current.returnApp == null,
                    volumeLow = volumeWarning(volume, volumeDismissedAt),
                    storeFailed = storeFailed,
                    askStoreFailed = askStoreFailed,
                  ),
                  conversation = conversationLabel(expiresAt, System.currentTimeMillis()),
                  askHint = askHint(ride),
                )
                HomeScreen(
                  ui = ui,
                  onTapToTalk = graph::tapToTalk,
                  onUser = { tabs.open(Tab.SETTINGS, UserKey) },
                  onBanner = { tabs.open(Tab.SETTINGS, if (it == Banner.NO_KEY) ConnectionKey else ReturnAppKey) },
                  onNewConversation = graph.controller::resetConversation,
                  modifier = Modifier.safeDrawingPadding(),
                  onDismissVolume = { volume?.let { graph.dismissVolumeWarning(it.current) } },
                  onDismissAskFailure = graph.askLog::dismiss,
                  ride = {
                    RideCard(
                      ride?.let { rideCard(it, now, questions) },
                      onStart = { sheet = RideSheet.START },
                      // Only a change that happened is told (Android #19).
                      onPause = {
                        scope.launch { if (graph.rides.pause()) Toast.makeText(context, "一時停止しました", Toast.LENGTH_SHORT).show() }
                      },
                      onResume = {
                        scope.launch { if (graph.rides.resume()) Toast.makeText(context, "再開しました", Toast.LENGTH_SHORT).show() }
                      },
                      onEnd = { sheet = RideSheet.END },
                      onMemo = { ride?.let { tabs.push(RideMemoKey(it.id)) } },
                    )
                  },
                  // The place is read when the button is pressed (slice 3 spec §4.3); a second press while it is read adds
                  // nothing, and a tab moved to meanwhile is left as it is (Android #57).
                  onNote = ride?.let { active ->
                    {
                      scope.launch {
                        val draft = graph.notes.draft(active.id)
                        tabs.pushOn(Tab.HOME, NewNoteKey(draft)) { it is NewNoteKey }
                      }
                    }
                  },
                )
              }
              entry<RecordsKey> {
                val rideList by graph.recordsList.collectAsStateWithLifecycle()
                RideListScreen(
                  rideList?.let { rideSections(it, System.currentTimeMillis(), ZoneId.systemDefault()) },
                  onRide = { tabs.push(RideKey(it)) },
                  modifier = Modifier.safeDrawingPadding(),
                )
              }
              entry<RideKey> { key ->
                RideDetailRoute(
                  graph.records, graph.rides, current,
                  updateSettings = { next -> scope.launch { graph.settings.update(next) } },
                  rideId = key.rideId,
                  onBack = { tabs.back() },
                  onAsk = { tabs.push(AskKey(key.rideId, it)) },
                  onFullMap = { mapFull = it },
                  onMemo = { tabs.push(RideMemoKey(key.rideId)) },
                  modifier = Modifier.safeDrawingPadding(),
                  onNote = { tabs.push(NoteKey(key.rideId, it)) },
                )
              }
              entry<AskKey> { key ->
                AskDetailRoute(
                  graph.records, graph.askEdits, key.rideId, key.askId,
                  onBack = { tabs.back() },
                  // ‹ › stay within the ride's asks: the shown ask is swapped, so Back still goes to SC-06.
                  onMove = { other -> tabs.current[tabs.current.lastIndex] = AskKey(key.rideId, other) },
                  onMemo = { tabs.push(AskMemoKey(key.rideId, key.askId)) },
                  modifier = Modifier.safeDrawingPadding(),
                )
              }
              entry<RideMemoKey> { key ->
                RideMemoRoute(graph.records, graph.rides, key.rideId, onClose = { tabs.back() }, modifier = Modifier.safeDrawingPadding())
              }
              entry<AskMemoKey> { key ->
                AskMemoRoute(graph.records, graph.askEdits, key.rideId, key.askId, onClose = { tabs.back() }, modifier = Modifier.safeDrawingPadding())
              }
              entry<NewNoteKey> { key ->
                NewNoteRoute(graph.records, graph.notes, key.draft, onClose = { tabs.back() }, modifier = Modifier.safeDrawingPadding(), voice = noteVoice)
              }
              entry<NoteKey> { key ->
                NoteRoute(graph.records, graph.notes, key.rideId, key.noteId, onClose = { tabs.back() }, modifier = Modifier.safeDrawingPadding(), voice = noteVoice)
              }
              entry<SettingsKey> {
                SettingsScreen(
                  current, apiKey,
                  onConnection = { tabs.push(ConnectionKey) },
                  onUser = { tabs.push(UserKey) },
                  onRideRecording = { tabs.push(RideRecordingKey) },
                  onReturnApp = { tabs.push(ReturnAppKey) },
                  onVolume = { tabs.push(VolumeKey) },
                  onRecordings = { tabs.push(RecordingsKey) },
                  version = BuildConfig.VERSION_NAME,
                  modifier = Modifier.safeDrawingPadding(),
                )
              }
              entry<RideRecordingKey> {
                // AppGraph moves the recording ride's deadline when the setting changes (US-C-08.005, Android #33).
                RideRecordingScreen(current, { next -> scope.launch { graph.settings.update(next) } }, Modifier.safeDrawingPadding())
              }
              entry<VolumeKey> {
                VolumeScreen(current, { next -> scope.launch { graph.settings.update(next) } }, Modifier.safeDrawingPadding())
              }
              entry<RecordingsKey> {
                val check = graph.recordingCheck
                val trial by graph.trialState.collectAsStateWithLifecycle()
                val items by check.items.collectAsStateWithLifecycle()
                val playing by check.playing.collectAsStateWithLifecycle()
                val message by check.message.collectAsStateWithLifecycle()
                // Leaving SC-12 drops the trial and its playback; an answer being read out goes on.
                DisposableEffect(Unit) { onDispose { check.leave() } }
                RecordingsScreen(
                  RecordingsUi(trial, items, playing, isBusy(state), message),
                  onTrial = check::toggleTrial,
                  onPlay = check::play,
                  onStop = check::stopPlaying,
                  onDeleteAll = check::deleteAll,
                  modifier = Modifier.safeDrawingPadding(),
                )
              }
              entry<ConnectionKey> {
                ConnectionScreen(
                  apiKey = apiKey,
                  urlMissing = graph.apiBaseUrl.isEmpty(),
                  check = { key -> ApiClient(graph.apiBaseUrl, key, current.userName, graph.http).checkKey() },
                  save = { graph.apiKeys.save(it) },
                  modifier = Modifier.safeDrawingPadding(),
                )
              }
              entry<UserKey> {
                UserScreen(current.userName, { name -> scope.launch { graph.settings.update(current.copy(userName = name)) } }, Modifier.safeDrawingPadding())
              }
              entry<ReturnAppKey> {
                var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
                LaunchedEffect(Unit) { apps = graph.launcher.launchableApps() }
                ReturnAppScreen(current, apps, { next -> scope.launch { graph.settings.update(next) } }, Modifier.safeDrawingPadding())
              }
            }
          // Each tab keeps the saved state of its screens (typed text, scroll) while another tab is shown (Android #34).
          val entries = mapOf(
            Tab.HOME to rememberDecoratedNavEntries(homeStack, listOf(rememberSaveableStateHolderNavEntryDecorator()), screens),
            Tab.RECORDS to rememberDecoratedNavEntries(recordsStack, listOf(rememberSaveableStateHolderNavEntryDecorator()), screens),
            Tab.SETTINGS to rememberDecoratedNavEntries(settingsStack, listOf(rememberSaveableStateHolderNavEntryDecorator()), screens),
          )
          NavDisplay(
            entries = entries.getValue(tabs.tab),
            modifier = Modifier.padding(inner).consumeWindowInsets(inner),
            onBack = { tabs.back() },
          )
        }
        when (sheet) {
          RideSheet.START -> StartRideSheet(
            locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            onStart = { name ->
              sheet = null
              scope.launch {
                // Another ride may have been resumed meanwhile, or the database not yet read (Android #19).
                if (!graph.rides.start(name)) Toast.makeText(context, "走行を開始できませんでした", Toast.LENGTH_SHORT).show()
              }
            },
            onDismiss = { sheet = null },
          )
          RideSheet.END -> ride?.let { active ->
            EndRideSheet(
              endSummary(active, now, ZoneId.systemDefault(), questions),
              active.name,
              onEnd = { name ->
                sheet = null
                scope.launch { graph.rides.end(name) }
              },
              onDismiss = { sheet = null },
            )
          }
          null -> Unit
        }
        // The ride may end elsewhere (deadline, shutdown) while SC-04 is open.
        LaunchedEffect(ride == null) { if (ride == null && sheet == RideSheet.END) sheet = null }
        Column(Modifier.fillMaxSize()) {
          AskOverlay(
            state, System::currentTimeMillis, graph.controller::stopRecording, graph.controller::cancel,
            volumeDismissedAt = volumeDismissedAt, onDismissVolume = graph::dismissVolumeWarning,
          )
        }
      }
    }
  }
}
