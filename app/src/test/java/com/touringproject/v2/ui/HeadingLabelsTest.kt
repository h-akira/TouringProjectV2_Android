package com.touringproject.v2.ui

import com.touringproject.v2.location.Fix
import com.touringproject.v2.location.HeadingParams
import com.touringproject.v2.location.HeadingResult
import com.touringproject.v2.location.HeadingView
import com.touringproject.v2.location.NoHeading
import org.junit.Assert.assertEquals
import org.junit.Test

// Slice 5 spec §4.5 and the wording settled for plan 4: SC-22's card.
class HeadingLabelsTest {
  private val here = Fix(35.0, 139.0, 0, 6.2)
  private val reference = Fix(35.0, 139.0, 0, 5.0)

  @Test
  fun the_sixteen_points_are_the_backends() {
    assertEquals("北", compass16(0.0))
    assertEquals("北", compass16(11.2))
    assertEquals("北北東", compass16(11.3))
    assertEquals("北西", compass16(315.0))
    assertEquals("北北西", compass16(348.7))
    assertEquals("北", compass16(359.9))
    assertEquals("南西", compass16(-135.0))
  }

  @Test
  fun a_heading_tells_its_direction_sides_and_material() {
    val ui = headingCheckUi(HeadingView(HeadingResult.Found(337.6, reference, 16.4), here, fromRide = true, referenceAgeMs = 3_400), HeadingParams())
    assertEquals(
      HeadingCheckUi(
        "北北西へ（338°）",
        "右手 東北東 ／ 左手 西南西",
        "位置の精度 6m · 基準の点 16m 手前（3秒前）",
        null,
        "走行のルートから求めています。いま質問すると、この方位を AI に渡します。",
      ),
      ui,
    )
  }

  @Test
  fun a_reference_older_than_30_seconds_says_the_heading_is_held() {
    val held = headingCheckUi(HeadingView(HeadingResult.Found(315.0, reference, 18.0), here, true, 150_000), HeadingParams())
    assertEquals("位置の精度 6m · 基準の点 18m 手前（2分前。止まる直前の向きを保っています）", held.detail)
    assertEquals("右手 北東 ／ 左手 南西", held.sides)
    val recent = headingCheckUi(HeadingView(HeadingResult.Found(315.0, reference, 18.0), here, true, 30_000), HeadingParams())
    assertEquals("位置の精度 6m · 基準の点 18m 手前（30秒前）", recent.detail)
  }

  @Test
  fun degrees_that_round_to_360_are_0_and_an_unknown_accuracy_says_so() {
    val ui = headingCheckUi(HeadingView(HeadingResult.Found(359.7, reference, 15.0), here.copy(accuracy = null), false, 5_000), HeadingParams())
    assertEquals("北へ（0°）", ui.title)
    assertEquals("位置の精度 不明 · 基準の点 15m 手前（5秒前）", ui.detail)
    assertEquals("この画面を開いてから集めた位置で、走行中と同じに求めています。画面を離れると、集めた位置は捨てます。", ui.source)
  }

  @Test
  fun no_heading_tells_why_with_the_parameters_of_the_moment() {
    val params = HeadingParams(referenceMeters = 15, accuracyMeters = 20, holdMinutes = 10)
    fun reason(why: NoHeading, at: Fix? = here) = headingCheckUi(HeadingView(HeadingResult.None(why), at, false, null), params)
    assertEquals(HeadingCheckUi("方位なし", null, null, "位置が取れていません", reason(NoHeading.NO_POSITION, null).source), reason(NoHeading.NO_POSITION, null))
    assertEquals("位置の精度が悪いため使いません（精度 35m・上限 20m）", reason(NoHeading.POOR_ACCURACY, here.copy(accuracy = 35.0)).reason)
    assertEquals("位置の精度 35m", reason(NoHeading.POOR_ACCURACY, here.copy(accuracy = 35.0)).detail)
    assertEquals("まだ基準の距離（15m）ほど動いていません", reason(NoHeading.NOT_MOVED).reason)
    assertEquals("止まってから、向きを保つ上限（10分）を超えました", reason(NoHeading.HELD_TOO_LONG).reason)
  }
}
