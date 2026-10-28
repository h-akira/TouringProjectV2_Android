package com.touringproject.v2.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.records.SaveSummary
import com.touringproject.v2.recordings.KEEP_RECORDINGS
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.MAX_RECORDING_HOURS_CHOICES
import com.touringproject.v2.settings.RECORD_LIMIT_SECONDS_CHOICES
import com.touringproject.v2.settings.ReturnTiming
import com.touringproject.v2.settings.START_CUE_MS_CHOICES
import com.touringproject.v2.settings.Settings
import com.touringproject.v2.settings.USER_NAME_RE
import com.touringproject.v2.settings.VOLUME_DB_CHOICES
import com.touringproject.v2.settings.maskKey
import com.touringproject.v2.settings.withRecentApp
import com.touringproject.v2.sync.Peek
import kotlinx.coroutines.launch

private val Blue = Color(0xFF1F4FD1)
private val Red = Color(0xFFD42A2A)

@Composable
/** A row without onClick only shows its value. */
private fun SettingsRow(title: String, value: String, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
  Surface(
    modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    shape = RoundedCornerShape(12.dp),
    border = BorderStroke(1.dp, Color(0xFFCCCCCC)),
  ) {
    Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(title, Modifier.weight(1f), fontSize = 18.sp)
      Text(value, color = Color(0xFF666666), fontSize = 18.sp)
    }
  }
}

// SC-09 (slice 1: the settings the slice needs).
@Composable
fun SettingsScreen(
  settings: Settings,
  apiKey: String?,
  onConnection: () -> Unit,
  onUser: () -> Unit,
  onRideRecording: () -> Unit,
  onReturnApp: () -> Unit,
  onVolume: () -> Unit,
  onRecordings: () -> Unit,
  /** The app's version, raised by hand for every new build put on the phones. */
  version: String,
  modifier: Modifier = Modifier,
  /** SC-14's row: 保存済み or 未送信あり (slice 4 spec §7.1). */
  saved: String = "",
  onData: () -> Unit = {},
  onRecordingSettings: () -> Unit = {},
  onHeadingCheck: () -> Unit = {},
) {
  val returnValue = if (!settings.returnEnabled) "戻らない" else settings.returnApp?.label ?: "未設定"
  // Scrolls: the list outgrows a small screen.
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SettingsRow("接続の設定", apiKey?.let(::maskKey) ?: "未設定", onConnection)
    SettingsRow("ユーザー名", settings.userName, onUser)
    SettingsRow("☁️ データの保存", saved, onData)
    SettingsRow("走行の記録", "最長 ${settings.maxRecordingHours}時間", onRideRecording)
    SettingsRow("応答後に戻るアプリ", returnValue, onReturnApp)
    // Next to the other settings of an ask, as the mock puts it (slice 5 spec §14).
    SettingsRow("録音の設定", recordingRowValue(settings), onRecordingSettings)
    SettingsRow("回答の音量", "+${settings.answerVolumeDb}dB", onVolume)
    SettingsRow("録音の確認", "", onRecordings)
    // Under the other check, as the mock's 確認 section has them (slice 5 spec §14).
    SettingsRow("位置と方位の確認", "", onHeadingCheck)
    SettingsRow("バージョン", version, null, Modifier.testTag("versionRow"))
  }
}

/**
 * SC-14 (slice 4 spec §7.1). recording: a ride records now, and its last points go at its pause or end. active: a ride
 * records or is paused, so reading the cloud again waits for its end. reading: a read of the cloud is under way.
 */
data class DataUi(
  /** null until what is left has been counted: nothing is said of what is saved meanwhile (Android #75). */
  val summary: SaveSummary?,
  val lastSaved: String,
  val sending: Boolean,
  val recording: Boolean,
  val active: Boolean,
  val reading: Boolean,
  val userName: String,
  /** What the last クラウドから読み直す came to; null before one. */
  val message: String?,
  /** The recordings kept on the device for SC-12. */
  val recordings: Int,
)

@Composable
private fun DataRow(title: String, value: String) {
  Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, Modifier.weight(1f), fontSize = 16.sp)
    Text(value, color = Color(0xFF666666), fontSize = 16.sp)
  }
}

// SC-14
@Composable
fun DataScreen(ui: DataUi, onSave: () -> Unit, onUser: () -> Unit, onReread: () -> Unit, modifier: Modifier = Modifier) {
  val muted = Color(0xFF666666)
  val summary = ui.summary
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (summary != null) Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color(0xFFCCCCCC))) {
      Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (summary.unsent == 0) {
          Text("● すべてクラウドに保存済み", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("saveState"))
          Text(ui.lastSaved, color = muted, fontSize = 14.sp)
        } else {
          Text("● 未送信の記録があります", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("saveState"))
          Text("${unsentBreakdown(summary)}。通信できるようになると自動で送ります。", color = muted, fontSize = 14.sp, modifier = Modifier.testTag("unsentBreakdown"))
          Button(onClick = onSave, enabled = !ui.sending, modifier = Modifier.fillMaxWidth().testTag("saveNow")) {
            Text(if (ui.sending) "送っています…" else "今すぐ保存", fontSize = 16.sp)
          }
        }
        if (ui.recording) Text("記録中の走行は、一時停止か終了で残りを送ります。", color = muted, fontSize = 14.sp, modifier = Modifier.testTag("recordingNote"))
      }
    }
    SettingsRow("ユーザー名", ui.userName, onUser)
    Button(
      onClick = onReread,
      enabled = !ui.active && !ui.reading,
      modifier = Modifier.fillMaxWidth().testTag("reread"),
      colors = ButtonDefaults.buttonColors(containerColor = Blue),
    ) { Text(if (ui.reading) "読み込んでいます…" else "クラウドから読み直す", fontSize = 16.sp) }
    if (ui.active) Text("走行を終えてから読み直せます", color = muted, fontSize = 14.sp)
    ui.message?.let { Text(it, modifier = Modifier.testTag("dataMessage")) }
    Text("保存している内容", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    DataRow("会話の文字・ルート・メモ・地点メモ", "端末＋クラウド · すべて")
    DataRow("録音の音声", "端末のみ · 直近${KEEP_RECORDINGS}件")
    Text(
      "録音の音声は 設定 > 録音の確認 でだけ聞けます。機種を変えても、同じユーザー名を設定すれば記録が戻ります。音声はクラウドに残さないため戻りません。",
      color = muted,
      fontSize = 14.sp,
    )
    Text("件数", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    DataRow("走行", summary?.rides?.toString().orEmpty())
    DataRow("質問", summary?.asks?.toString().orEmpty())
    DataRow("端末の音声", "${ui.recordings} / $KEEP_RECORDINGS")
  }
}

// SC-21: how much the Backend raises the answer audio. Takes effect from the next question.
@Composable
fun VolumeScreen(settings: Settings, onUpdate: (Settings) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("回答の音声を上げる量", fontSize = 18.sp)
    Text("ナビの案内や音楽より小さく聞こえるときに上げます。次の質問から変わります。", color = Color(0xFF666666), fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      VOLUME_DB_CHOICES.forEach { db ->
        FilterChip(
          selected = settings.answerVolumeDb == db,
          onClick = { onUpdate(settings.copy(answerVolumeDb = db)) },
          label = { Text("+$db", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
          modifier = Modifier.weight(1f).testTag("volume-$db"),
        )
      }
    }
    Text("単位は dB です。+6（最大）で、+0 のおよそ2倍の大きさです。それでも小さいときは、スマホのメディアの音量を上げてください。", color = Color(0xFF666666), fontSize = 14.sp)
  }
}

// SC-16 (slice 2 spec §7.8): the longest a ride may record without a pause. A recording ride follows at once.
@Composable
fun RideRecordingScreen(settings: Settings, onUpdate: (Settings) -> Unit, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("連続して記録できる最長時間", fontSize = 18.sp)
    Text(
      "開始または再開してから一時停止せずに記録し続けた時間がこれを超えると、終了し忘れとみなして自動で終了・保存します。一時停止して再開すると数え直します",
      color = Color(0xFF666666),
      fontSize = 14.sp,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      MAX_RECORDING_HOURS_CHOICES.forEach { hours ->
        FilterChip(
          selected = settings.maxRecordingHours == hours,
          onClick = { onUpdate(settings.copy(maxRecordingHours = hours)) },
          label = { Text("${hours}時間", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
          modifier = Modifier.weight(1f).testTag("maxHours-$hours"),
        )
      }
    }
    Text(
      "泊まりがけでも、休憩や宿泊で一時停止していれば数え直すので、ふだんは変える必要はありません。記録中の走行にも、変更はすぐに反映されます。",
      color = Color(0xFF666666),
      fontSize = 14.sp,
    )
  }
}

/**
 * SC-11 (slice 5 spec §6): how long a question may be recorded, and the start cue. Each change is saved at once and
 * takes effect from the next question and trial. onReset puts both back (SC-11's 「既定値に戻す」, told by a toast).
 */
@Composable
fun RecordingSettingsScreen(settings: Settings, onUpdate: (Settings) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
  val muted = Color(0xFF666666)
  val limit = settings.recordLimitSeconds
  val choices = RECORD_LIMIT_SECONDS_CHOICES
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("録音の上限", fontSize = 18.sp)
    Text("インカムのボタンを再度押さなくても、この時間で自動的に送ります", color = muted, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
      Button(
        onClick = { onUpdate(settings.copy(recordLimitSeconds = choices[choices.indexOf(limit) - 1])) },
        enabled = limit > choices.first(),
        modifier = Modifier.testTag("limitDown"),
      ) { Text("−", fontSize = 20.sp) }
      Text("${limit}秒", Modifier.weight(1f).testTag("limitValue"), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
      Button(
        onClick = { onUpdate(settings.copy(recordLimitSeconds = choices[choices.indexOf(limit) + 1])) },
        enabled = limit < choices.last(),
        modifier = Modifier.testTag("limitUp"),
      ) { Text("＋", fontSize = 20.sp) }
    }
    Text("開始の合図", fontSize = 18.sp)
    Text("録音が始まったことを知らせる音の長さ。インカムは音の出だしを落とすので、短いと聞こえにくいことがあります", color = muted, fontSize = 14.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      START_CUE_MS_CHOICES.forEach { ms ->
        FilterChip(
          selected = settings.startCueMs == ms,
          onClick = { onUpdate(settings.copy(startCueMs = ms)) },
          label = { Text(cueLabel(ms), fontSize = 13.sp) },
          modifier = Modifier.weight(1f).testTag("cue-$ms"),
        )
      }
    }
    Button(onClick = onReset, modifier = Modifier.fillMaxWidth().testTag("recordingDefaults")) { Text("既定値に戻す", fontSize = 16.sp) }
  }
}

enum class KeyCheck(val message: String) {
  SAVED("保存しました"),
  INVALID("キーが正しくありません。元のキーのままです。"),
  UNREACHABLE("サーバーに繋がりません。元のキーのままです。"),
  SAVE_FAILED("キーを保存できませんでした。元のキーのままです。"),
}

/** Checks the key against the Backend, then saves it; the old key stays when either step fails. */
suspend fun checkAndSave(key: String, check: suspend (String) -> Unit, save: suspend (String) -> Unit): KeyCheck {
  try {
    check(key)
  } catch (e: ApiError) {
    return if (e.kind == ApiErrorKind.INVALID_KEY) KeyCheck.INVALID else KeyCheck.UNREACHABLE
  } catch (e: IllegalArgumentException) {
    // OkHttp refuses header values it cannot send (e.g. full-width characters pasted into the key).
    return KeyCheck.INVALID
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (e: Exception) {
    return KeyCheck.UNREACHABLE
  }
  return try {
    save(key)
    KeyCheck.SAVED
  } catch (e: kotlinx.coroutines.CancellationException) {
    throw e
  } catch (e: Exception) {
    // e.g. the Keystore refuses to seal it (issue #5): the server was reachable, so do not say it was not.
    KeyCheck.SAVE_FAILED
  }
}

// SC-13
@Composable
fun ConnectionScreen(
  apiKey: String?,
  urlMissing: Boolean,
  check: suspend (String) -> Unit,
  save: suspend (String?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  // Not saveable: the key being typed is not put into the saved state, which the system may write out.
  var input by remember { mutableStateOf("") }
  var checking by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  var confirmingDelete by remember { mutableStateOf(false) }
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("接続キー", color = Color(0xFF666666), fontSize = 16.sp)
    Text(apiKey?.let(::maskKey) ?: "未設定", fontSize = 20.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("currentKey"))
    Text("身内で共通のキーを使ってかまいません。記録はユーザー名で分かれます。", color = Color(0xFF666666), fontSize = 14.sp)
    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("keyInput"), placeholder = { Text("キーを貼り付け") }, singleLine = true)
    Button(
      onClick = {
        val key = input.trim()
        if (key.isEmpty()) return@Button
        checking = true
        scope.launch {
          val result = checkAndSave(key, check, save)
          if (result == KeyCheck.SAVED) input = ""
          message = result.message
          checking = false
        }
      },
      enabled = !checking,
      modifier = Modifier.fillMaxWidth().testTag("saveKey"),
      colors = ButtonDefaults.buttonColors(containerColor = Blue),
    ) { Text(if (checking) "確かめています…" else "確かめて保存", fontSize = 18.sp) }
    if (apiKey != null) {
      Button(onClick = { confirmingDelete = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Red)) {
        Text("キーを削除", fontSize = 18.sp)
      }
    }
    message?.let { Text(it, Modifier.testTag("keyMessage")) }
    if (urlMissing) Text("API の URL が設定されていません（local.properties の trv2.apiBaseUrl）", color = Red)
  }
  // DL-01 (connection key)
  if (confirmingDelete) {
    DeleteDialog(
      "接続キーを削除しますか？", null, "削除する",
      onConfirm = { confirmingDelete = false; scope.launch { save(null) } },
      onDismiss = { confirmingDelete = false },
    )
  }
}

/** Why the user name cannot change now (slice 4 spec §6.4, US-E-06.008). */
enum class UserBlock(val message: String) {
  RIDE("走行を終えてから変えられます"),
  UNSENT("未送信の記録があります。先に「データの保存」で送ってください"),
}

/**
 * SC-18. peek reads the new name's first page (null when it cannot), and DL-03 tells from it whether the name has
 * records (slice 4 spec §6.4). onChange starts the change after DL-03; reading is true while a read of the cloud is under
 * way, and message is what the last change came to. counted is false until what is left to send has been counted, and
 * the name cannot change meanwhile (Android #75).
 */
@Composable
fun UserScreen(
  userName: String,
  block: UserBlock?,
  reading: Boolean,
  message: String?,
  peek: suspend (String) -> Peek?,
  onChange: (Peek) -> Unit,
  onData: () -> Unit,
  modifier: Modifier = Modifier,
  counted: Boolean = true,
) {
  val scope = rememberCoroutineScope()
  // Saveable, so a name being typed survives a trip to another tab (Android #34).
  var input by rememberSaveable { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  var checking by remember { mutableStateOf(false) }
  var confirming by remember { mutableStateOf<Peek?>(null) }
  Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("今のユーザー名", color = Color(0xFF666666), fontSize = 16.sp)
    Text(userName, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    Text("記録はユーザー名ごとに分かれます。別の端末で同じユーザー名を設定すると、記録を引き継げます。控えておいてください。", color = Color(0xFF666666), fontSize = 14.sp)
    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("userInput"), placeholder = { Text("新しいユーザー名") }, singleLine = true)
    error?.let { Text(it, color = Red, modifier = Modifier.testTag("userError")) }
    Button(
      onClick = {
        val name = input.trim()
        error = when {
          !USER_NAME_RE.matches(name) -> "英小文字と数字の3〜20文字にしてください"
          name == userName -> "今のユーザー名と同じです"
          else -> null
        }
        if (error != null) return@Button
        checking = true
        scope.launch {
          val peeked = peek(name)
          checking = false
          if (peeked == null) error = "読み込めませんでした" else confirming = peeked
        }
      },
      enabled = counted && block == null && !reading && !checking,
      modifier = Modifier.fillMaxWidth().testTag("changeUser"),
      colors = ButtonDefaults.buttonColors(containerColor = Blue),
    ) { Text(if (reading || checking) "読み込んでいます…" else "変更する", fontSize = 18.sp) }
    block?.let { Text(it.message, modifier = Modifier.testTag("userBlock")) }
    if (block == UserBlock.UNSENT) TextButton(onClick = onData, modifier = Modifier.testTag("toData")) { Text("データの保存へ") }
    message?.let { Text(it, modifier = Modifier.testTag("userMessage")) }
    Text(
      "変えると、今の記録は引き継がれず、新しいユーザー名の記録が表示されます。今の記録は消えず、元のユーザー名に戻せばまた見られます。",
      color = Color(0xFF666666),
      fontSize = 14.sp,
    )
  }
  // DL-03: worded by whether the new name has records (the mock's two states).
  confirming?.let { peeked ->
    val name = peeked.userName
    AlertDialog(
      onDismissRequest = { confirming = null },
      title = { Text(if (peeked.exists) "既にあるユーザー名です" else "ユーザー名を変えますか？") },
      text = {
        Text(
          if (peeked.exists) {
            "「$name」の記録が既にあります。このユーザー名に切り替えると、その記録が表示されます。今の「$userName」の記録は引き継がれません。元のユーザー名に戻せば、また見られます。"
          } else {
            "「$name」は新しいユーザー名です。今の「$userName」の記録は引き継がれず、記録が空の状態から始まります。元のユーザー名に戻せば、今の記録はまた見られます。"
          },
          modifier = Modifier.testTag("userDialogText"),
        )
      },
      confirmButton = {
        TextButton(onClick = { confirming = null; input = ""; onChange(peeked) }, Modifier.testTag("confirmUser")) {
          Text(if (peeked.exists) "切り替える" else "変更する")
        }
      },
      dismissButton = { TextButton(onClick = { confirming = null }) { Text("やめる") } },
    )
  }
}

private val TIMINGS = listOf(ReturnTiming.SENT to "送ったらすぐ", ReturnTiming.ANSWERED to "回答が届いたら", ReturnTiming.SPOKEN to "読み上げが終わったら")

fun filterApps(apps: List<LaunchableApp>, query: String): List<LaunchableApp> {
  val q = query.trim().lowercase()
  return if (q.isEmpty()) apps else apps.filter { q in it.label.lowercase() || q in it.packageName.lowercase() }
}

// SC-10
@Composable
fun ReturnAppScreen(settings: Settings, apps: List<LaunchableApp>, onUpdate: (Settings) -> Unit, modifier: Modifier = Modifier) {
  var filter by remember { mutableStateOf("") }
  val choose = { app: LaunchableApp -> onUpdate(settings.copy(returnApp = app, recentApps = withRecentApp(settings.recentApps, app))) }
  LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    item {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("応答後にアプリへ戻る", fontSize = 18.sp)
        Switch(settings.returnEnabled, { onUpdate(settings.copy(returnEnabled = it)) }, Modifier.testTag("returnSwitch"))
      }
      Text("戻るのはインカムのボタンで始めた応答だけです（画面から質問したときは戻りません）。", color = Color(0xFF666666), fontSize = 13.sp)
    }
    if (settings.returnEnabled) {
      item {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TIMINGS.forEach { (timing, label) ->
            FilterChip(settings.returnTiming == timing, { onUpdate(settings.copy(returnTiming = timing)) }, { Text(label, fontSize = 13.sp) })
          }
        }
        Text("現在の戻り先: ${settings.returnApp?.label ?: "未設定"}", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("currentReturnApp"))
      }
      items(settings.recentApps, key = { "recent-" + it.packageName }) { app ->
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(app.label, Modifier.weight(1f).clickable { choose(app) }.padding(12.dp), fontSize = 16.sp)
          Text("✕", Modifier.clickable { onUpdate(settings.copy(recentApps = settings.recentApps.filter { it.packageName != app.packageName })) }.padding(12.dp), fontSize = 18.sp)
        }
      }
      item {
        OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().testTag("appFilter"), placeholder = { Text("アプリを絞り込む") }, singleLine = true)
      }
      items(filterApps(apps, filter), key = { it.packageName }) { app ->
        Column(Modifier.fillMaxWidth().clickable { choose(app) }.padding(vertical = 10.dp).testTag("app-${app.packageName}")) {
          Text(app.label, fontSize = 16.sp)
          Text(app.packageName, color = Color(0xFF888888), fontSize = 12.sp)
        }
      }
    }
  }
}
