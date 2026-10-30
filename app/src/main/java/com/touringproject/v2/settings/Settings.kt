package com.touringproject.v2.settings

import com.touringproject.v2.location.DEFAULT_HEADING_ACCURACY_M
import com.touringproject.v2.location.DEFAULT_HEADING_HOLD_MINUTES
import com.touringproject.v2.location.DEFAULT_HEADING_REFERENCE_M
import com.touringproject.v2.location.HeadingParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

// SC-11 (slice 5 spec §6): how long a question may be recorded, and how long its start cue sounds (0: none). The trial
// on SC-12 takes the same values; SC-19's voice input does not.
val RECORD_LIMIT_SECONDS_CHOICES = (10..60 step 5).toList()
const val DEFAULT_RECORD_LIMIT_SECONDS = 20
const val DEFAULT_RECORD_LIMIT_MS = DEFAULT_RECORD_LIMIT_SECONDS * 1_000L
val START_CUE_MS_CHOICES = listOf(0, 500, 1_000, 1_500, 2_000, 3_000)
const val DEFAULT_START_CUE_MS = 1_500

// SC-22 (slice 5 spec §4.3): how the heading is worked out from the route.
val HEADING_REFERENCE_M_CHOICES = listOf(10, 15, 20, 30)
val HEADING_ACCURACY_M_CHOICES = listOf(10, 20, 30, 50)
val HEADING_HOLD_MINUTES_CHOICES = listOf(2, 5, 10, 30)

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
  val recordLimitSeconds: Int = DEFAULT_RECORD_LIMIT_SECONDS,
  val startCueMs: Int = DEFAULT_START_CUE_MS,
  val headingReferenceMeters: Int = DEFAULT_HEADING_REFERENCE_M,
  val headingAccuracyMeters: Int = DEFAULT_HEADING_ACCURACY_M,
  val headingHoldMinutes: Int = DEFAULT_HEADING_HOLD_MINUTES,
)

/** The longest a question is recorded (SC-11). */
val Settings.recordLimitMs: Long get() = recordLimitSeconds * 1_000L

/** SC-11's 「既定値に戻す」: both of its values, and nothing else. */
fun Settings.withRecordingDefaults(): Settings = copy(recordLimitSeconds = DEFAULT_RECORD_LIMIT_SECONDS, startCueMs = DEFAULT_START_CUE_MS)

val Settings.headingParams: HeadingParams get() = HeadingParams(headingReferenceMeters, headingAccuracyMeters, headingHoldMinutes)

/** SC-22's 「既定値に戻す」: its three values, and nothing else. */
fun Settings.withHeadingDefaults(): Settings = copy(
  headingReferenceMeters = DEFAULT_HEADING_REFERENCE_M,
  headingAccuracyMeters = DEFAULT_HEADING_ACCURACY_M,
  headingHoldMinutes = DEFAULT_HEADING_HOLD_MINUTES,
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
  fun choice(key: String, choices: List<Int>, default: Int) =
    runCatching { obj[key]?.jsonPrimitive?.intOrNull }.getOrNull()?.takeIf { it in choices } ?: default
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
    recordLimitSeconds = choice("recordLimitSeconds", RECORD_LIMIT_SECONDS_CHOICES, DEFAULT_RECORD_LIMIT_SECONDS),
    startCueMs = choice("startCueMs", START_CUE_MS_CHOICES, DEFAULT_START_CUE_MS),
    headingReferenceMeters = choice("headingReferenceMeters", HEADING_REFERENCE_M_CHOICES, DEFAULT_HEADING_REFERENCE_M),
    headingAccuracyMeters = choice("headingAccuracyMeters", HEADING_ACCURACY_M_CHOICES, DEFAULT_HEADING_ACCURACY_M),
    headingHoldMinutes = choice("headingHoldMinutes", HEADING_HOLD_MINUTES_CHOICES, DEFAULT_HEADING_HOLD_MINUTES),
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
    recordLimitSeconds = settings.recordLimitSeconds,
    startCueMs = settings.startCueMs,
    headingReferenceMeters = settings.headingReferenceMeters,
    headingAccuracyMeters = settings.headingAccuracyMeters,
    headingHoldMinutes = settings.headingHoldMinutes,
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
  val recordLimitSeconds: Int,
  val startCueMs: Int,
  val headingReferenceMeters: Int,
  val headingAccuracyMeters: Int,
  val headingHoldMinutes: Int,
)

fun withRecentApp(recent: List<LaunchableApp>, app: LaunchableApp, max: Int = 5): List<LaunchableApp> =
  (listOf(app) + recent.filter { it.packageName != app.packageName }).take(max)

fun maskKey(key: String): String = if (key.length <= 4) "••••" else "••••••••${key.takeLast(4)}"

/**
 * The fields to changed from from, laid onto latest: a screen holds a copy that may be old, so what it did not touch
 * is the newest value, not the copy's. The user name is always latest's: it changes through the repository's save only
 * (slice 6 plan 5, R10-03).
 */
fun applyChange(from: Settings, to: Settings, latest: Settings): Settings {
  fun fields(s: Settings): JsonObject = json.parseToJsonElement(encodeSettings(s)).jsonObject
  val before = fields(from)
  val after = fields(to)
  val merged = buildJsonObject {
    for ((key, value) in fields(latest)) {
      put(key, if (key != "userName" && after[key] != before[key]) after.getValue(key) else value)
    }
  }
  return decodeSettings(merged.toString()).first
}
