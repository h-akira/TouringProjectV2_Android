package com.touringproject.v2.ui

import com.touringproject.v2.location.HeadingParams
import com.touringproject.v2.location.HeadingResult
import com.touringproject.v2.location.HeadingView
import com.touringproject.v2.location.NoHeading
import kotlin.math.roundToInt

// How SC-22 tells the heading (slice 5 spec §4.5). The names of the directions are the Backend's, which it gives the AI.

private val COMPASS_16 = listOf(
  "北", "北北東", "北東", "東北東", "東", "東南東", "南東", "南南東", "南", "南南西", "南西", "西南西", "西", "西北西", "北西", "北北西",
)

/** The nearest of the sixteen points of the compass. */
fun compass16(degrees: Double): String = COMPASS_16[((((degrees % 360) + 360) % 360 + 11.25) / 22.5).toInt() % 16]

/** A reference point older than this means the bike has stood still: no point is added while it does. */
const val HELD_AFTER_MS = 30_000L

/** SC-22's card. sides is null without a heading; reason is null with one. */
data class HeadingCheckUi(val title: String, val sides: String?, val detail: String?, val reason: String?, val source: String)

private fun metres(value: Double) = "${value.roundToInt()}m"

private fun ago(ms: Long): String = if (ms < 60_000) "${ms / 1_000}秒前" else "${ms / 60_000}分前"

fun headingCheckUi(view: HeadingView, params: HeadingParams): HeadingCheckUi {
  val accuracy = view.here?.let { here -> "位置の精度 ${here.accuracy?.let(::metres) ?: "不明"}" }
  val source = if (view.fromRide) {
    "走行のルートから求めています。いま質問すると、この方位を AI に渡します。"
  } else {
    "この画面を開いてから集めた位置で、走行中と同じに求めています。画面を離れると、集めた位置は捨てます。"
  }
  return when (val result = view.result) {
    is HeadingResult.Found -> {
      val degrees = result.degrees
      // A reference a moment after the present (each fix's time stands a little differently) is new (Android #86).
      val age = (view.referenceAgeMs ?: 0).coerceAtLeast(0)
      val held = if (age > HELD_AFTER_MS) "。止まる直前の向きを保っています" else ""
      HeadingCheckUi(
        title = "${compass16(degrees)}へ（${degrees.roundToInt() % 360}°）",
        sides = "右手 ${compass16(degrees + 90)} ／ 左手 ${compass16(degrees - 90)}",
        detail = "$accuracy · 基準の点 ${metres(result.meters)} 手前（${ago(age)}$held）",
        reason = null,
        source = source,
      )
    }
    is HeadingResult.None -> HeadingCheckUi(
      title = "方位なし",
      sides = null,
      detail = accuracy,
      reason = when (result.reason) {
        NoHeading.NO_POSITION -> "位置が取れていません"
        NoHeading.POOR_ACCURACY ->
          "位置の精度が悪いため使いません（精度 ${view.here?.accuracy?.let(::metres) ?: "不明"}・上限 ${params.accuracyMeters}m）"
        NoHeading.NOT_MOVED -> "まだ基準の距離（${params.referenceMeters}m）ほど動いていません"
        NoHeading.HELD_TOO_LONG -> "止まってから、向きを保つ上限（${params.holdMinutes}分）を超えました"
      },
      source = source,
    )
  }
}
