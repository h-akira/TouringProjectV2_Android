package com.touringproject.v2.records

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavingTest {
  // Android #90: a summary no longer shown starts from nothing again, so coming back to SC-14 or SC-18 never shows an
  // old count; while it is still shown, or within 5 s of leaving, it stays.
  @Test
  fun a_summary_no_longer_shown_starts_from_nothing_again() = runTest {
    val counted = MutableStateFlow(SaveSummary(unreached = 1))
    val shared = counted.whileShown(backgroundScope)
    val shown = backgroundScope.launch { shared.collect {} }
    runCurrent()
    assertEquals(SaveSummary(unreached = 1), shared.value)
    shown.cancel()
    advanceTimeBy(4_000)
    runCurrent()
    assertEquals(SaveSummary(unreached = 1), shared.value)
    advanceTimeBy(1_001)
    runCurrent()
    assertNull(shared.value)
  }
}
