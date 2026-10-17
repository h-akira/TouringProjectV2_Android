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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.touringproject.v2.api.ApiError
import com.touringproject.v2.api.ApiErrorKind
import com.touringproject.v2.settings.LaunchableApp
import com.touringproject.v2.settings.ReturnTiming
import com.touringproject.v2.settings.Settings
import com.touringproject.v2.settings.USER_NAME_RE
import com.touringproject.v2.settings.VOLUME_DB_CHOICES
import com.touringproject.v2.settings.maskKey
import com.touringproject.v2.settings.withRecentApp
import kotlinx.coroutines.launch

private val Blue = Color(0xFF1F4FD1)
private val Red = Color(0xFFD42A2A)

@Composable
private fun SettingsRow(title: String, value: String, onClick: () -> Unit) {
  Surface(
    Modifier.fillMaxWidth().clickable(onClick = onClick),
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
  onReturnApp: () -> Unit,
  onVolume: () -> Unit,
  onRecordings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val returnValue = if (!settings.returnEnabled) "戻らない" else settings.returnApp?.label ?: "未設定"
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    SettingsRow("接続の設定", apiKey?.let(::maskKey) ?: "未設定", onConnection)
    SettingsRow("ユーザー名", settings.userName, onUser)
    SettingsRow("応答後に戻るアプリ", returnValue, onReturnApp)
    SettingsRow("回答の音量", "+${settings.answerVolumeDb}dB", onVolume)
    SettingsRow("録音の確認", "", onRecordings)
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
    AlertDialog(
      onDismissRequest = { confirmingDelete = false },
      title = { Text("接続キーを削除しますか？") },
      confirmButton = { TextButton(onClick = { confirmingDelete = false; scope.launch { save(null) } }) { Text("削除する") } },
      dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("やめる") } },
    )
  }
}

// SC-18
@Composable
fun UserScreen(userName: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
  var input by remember { mutableStateOf("") }
  var error by remember { mutableStateOf(false) }
  var confirming by remember { mutableStateOf<String?>(null) }
  Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("今のユーザー名", color = Color(0xFF666666), fontSize = 16.sp)
    Text(userName, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    Text("別の端末に記録を引き継ぐときに使います。控えておいてください。", color = Color(0xFF666666), fontSize = 14.sp)
    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth().testTag("userInput"), placeholder = { Text("新しいユーザー名") }, singleLine = true)
    if (error) Text("英小文字と数字の3〜20文字にしてください", color = Red, modifier = Modifier.testTag("userError"))
    Button(
      onClick = {
        val name = input.trim()
        error = !USER_NAME_RE.matches(name)
        if (!error) confirming = name
      },
      modifier = Modifier.fillMaxWidth().testTag("changeUser"),
      colors = ButtonDefaults.buttonColors(containerColor = Blue),
    ) { Text("変更する", fontSize = 18.sp) }
  }
  // DL-03
  confirming?.let { name ->
    AlertDialog(
      onDismissRequest = { confirming = null },
      title = { Text("「$name」に変えますか？") },
      text = { Text("今の記録は引き継ぎません。元の名前に戻せば、また見られます。同じ名前の記録が既にあれば、その記録に切り替わります。") },
      confirmButton = { TextButton(onClick = { confirming = null; input = ""; onChange(name) }) { Text("変える") } },
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
