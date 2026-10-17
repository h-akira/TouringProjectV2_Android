package com.touringproject.v2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.touringproject.v2.AppGraph
import com.touringproject.v2.api.ApiClient
import com.touringproject.v2.ask.isBusy
import com.touringproject.v2.settings.LaunchableApp
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable data object HomeKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data object ConnectionKey : NavKey
@Serializable data object UserKey : NavKey
@Serializable data object ReturnAppKey : NavKey
@Serializable data object VolumeKey : NavKey
@Serializable data object RecordingsKey : NavKey

@Composable
fun AppUi(graph: AppGraph) {
  val ready by graph.isReady.collectAsStateWithLifecycle()
  val state by graph.state.collectAsStateWithLifecycle()
  val settings by graph.settings.settings.collectAsStateWithLifecycle()
  val apiKey by graph.apiKeys.apiKey.collectAsStateWithLifecycle()
  val volumeDismissedAt by graph.volumeDismissedAt.collectAsStateWithLifecycle()
  val scope = rememberCoroutineScope()
  val backStack = rememberNavBackStack(HomeKey)
  val current = settings
  // Re-read on every phase change, so the label updates once an ask has renewed the conversation.
  val expiresAt = remember(state) { graph.conversation.expiresAt(System.currentTimeMillis()) }
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize()) {
      if (!ready || current == null) {
        Text("準備中…", Modifier.safeDrawingPadding())
      } else {
        NavDisplay(
          backStack = backStack,
          onBack = { backStack.removeLastOrNull() },
          entryProvider = entryProvider {
            entry<HomeKey> {
              val volume = rememberMediaVolume(volumeReadable(state.phase)) { graph.audioRoute.mediaVolume() }
              val ui = HomeUi(
                userName = current.userName,
                banner = homeBanner(
                  hasKey = apiKey != null,
                  returnAppMissing = current.returnEnabled && current.returnApp == null,
                  volumeLow = volumeWarning(volume, volumeDismissedAt),
                ),
                conversation = conversationLabel(expiresAt, System.currentTimeMillis()),
              )
              HomeScreen(
                ui = ui,
                onTapToTalk = graph::tapToTalk,
                onSettings = { backStack.add(SettingsKey) },
                onUser = { backStack.add(UserKey) },
                onBanner = { backStack.add(if (it == Banner.NO_KEY) ConnectionKey else ReturnAppKey) },
                onNewConversation = graph.controller::resetConversation,
                modifier = Modifier.safeDrawingPadding(),
                onDismissVolume = { volume?.let { graph.dismissVolumeWarning(it.current) } },
              )
            }
            entry<SettingsKey> {
              SettingsScreen(
                current, apiKey,
                onConnection = { backStack.add(ConnectionKey) },
                onUser = { backStack.add(UserKey) },
                onReturnApp = { backStack.add(ReturnAppKey) },
                onVolume = { backStack.add(VolumeKey) },
                onRecordings = { backStack.add(RecordingsKey) },
                modifier = Modifier.safeDrawingPadding(),
              )
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
                check = { key -> ApiClient(graph.apiBaseUrl, key, current.userName).checkKey() },
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
          },
        )
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
