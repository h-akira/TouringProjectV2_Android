package com.touringproject.v2.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

// Settings kept on the device. The connection key is stored separately (ApiKeyStore).
enum class ReturnTiming { SENT, ANSWERED, SPOKEN }

@Serializable
data class LaunchableApp(val packageName: String, val label: String)

// How much the Backend raises the answer audio (SC-21). The Backend accepts exactly these values; Polly stops at +6 dB.
val VOLUME_DB_CHOICES = listOf(0, 3, 6)
const val DEFAULT_VOLUME_DB = 6

// SC-16: how long a ride may record without a pause before it ends by itself (slice 2 spec §4.4, US-C-08.003).
val MAX_RECORDING_HOURS_CHOICES = listOf(6, 12, 24, 48)
const val DEFAULT_MAX_RECORDING_HOURS = 24

data class Settings(
  val userName: String,
  val returnEnabled: Boolean = true,
  val returnApp: LaunchableApp? = null,
  val returnTiming: ReturnTiming = ReturnTiming.SENT,
  val recentApps: List<LaunchableApp> = emptyList(),
  val answerVolumeDb: Int = DEFAULT_VOLUME_DB,
  val maxRecordingHours: Int = DEFAULT_MAX_RECORDING_HOURS,
  /** SC-06: whether the map shows the asks' pins; kept for the next time a ride is opened (slice 2 spec §7.5). */
  val showAskPins: Boolean = true,
  /** SC-06: whether the map shows the point memos' pins, kept like showAskPins (slice 3 spec §7.6). */
  val showNotePins: Boolean = true,
)

/**
 * The longest recording time each time the rider changes it, but not the value first loaded (US-C-08.005). AppGraph
 * follows it, so the recording ride's deadline moves even when SC-16 goes away mid-save (Android #33).
 */
fun maxRecordingChanges(settings: Flow<Settings?>): Flow<Int> =
  settings.mapNotNull { it?.maxRecordingHours }.distinctUntilChanged().drop(1)

val USER_NAME_RE = Regex("^[a-z0-9]{3,20}$")
private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
private val json = Json { ignoreUnknownKeys = true }

fun randomUserName(random: Random = Random.Default): String =
  (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

/** Reads saved settings; anything missing or broken falls back to its default. needsSave is true when a user name was made up. */
fun decodeSettings(raw: String?, random: Random = Random.Default): Pair<Settings, Boolean> {
  val obj: JsonObject = runCatching { json.parseToJsonElement(raw ?: "").jsonObject }.getOrElse { JsonObject(emptyMap()) }
  fun string(key: String) = runCatching { obj[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
  fun app(element: kotlinx.serialization.json.JsonElement?) =
    runCatching { element?.let { json.decodeFromJsonElement<LaunchableApp>(it) } }.getOrNull()
  val savedName = string("userName")?.takeIf { USER_NAME_RE.matches(it) }
  val settings = Settings(
    userName = savedName ?: randomUserName(random),
    returnEnabled = runCatching { obj["returnEnabled"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: true,
    returnApp = app(obj["returnApp"]),
    returnTiming = ReturnTiming.entries.firstOrNull { it.name.lowercase() == string("returnTiming") } ?: ReturnTiming.SENT,
    recentApps = runCatching { obj["recentApps"]?.jsonArray?.mapNotNull { app(it) } }.getOrNull() ?: emptyList(),
    answerVolumeDb = runCatching { obj["answerVolumeDb"]?.jsonPrimitive?.intOrNull }.getOrNull()
      ?.takeIf { it in VOLUME_DB_CHOICES } ?: DEFAULT_VOLUME_DB,
    maxRecordingHours = runCatching { obj["maxRecordingHours"]?.jsonPrimitive?.intOrNull }.getOrNull()
      ?.takeIf { it in MAX_RECORDING_HOURS_CHOICES } ?: DEFAULT_MAX_RECORDING_HOURS,
    showAskPins = runCatching { obj["showAskPins"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: true,
    showNotePins = runCatching { obj["showNotePins"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: true,
  )
  return settings to (savedName == null)
}

fun encodeSettings(settings: Settings): String = json.encodeToString(
  SavedSettings.serializer(),
  SavedSettings(
    userName = settings.userName,
    returnEnabled = settings.returnEnabled,
    returnApp = settings.returnApp,
    returnTiming = settings.returnTiming.name.lowercase(),
    recentApps = settings.recentApps,
    answerVolumeDb = settings.answerVolumeDb,
    maxRecordingHours = settings.maxRecordingHours,
    showAskPins = settings.showAskPins,
    showNotePins = settings.showNotePins,
  ),
)

@Serializable
private data class SavedSettings(
  val userName: String,
  val returnEnabled: Boolean,
  val returnApp: LaunchableApp?,
  val returnTiming: String,
  val recentApps: List<LaunchableApp>,
  val answerVolumeDb: Int,
  val maxRecordingHours: Int,
  val showAskPins: Boolean,
  val showNotePins: Boolean,
)

fun withRecentApp(recent: List<LaunchableApp>, app: LaunchableApp, max: Int = 5): List<LaunchableApp> =
  (listOf(app) + recent.filter { it.packageName != app.packageName }).take(max)

fun maskKey(key: String): String = if (key.length <= 4) "••••" else "••••••••${key.takeLast(4)}"
