package com.touringproject.v2.ui

import com.touringproject.v2.ask.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TabsTest {
  private var selected = Tab.HOME
  private val home = mutableListOf("home")
  private val records = mutableListOf("records")
  private val settings = mutableListOf("settings")
  private val tabs = TabStacks(mapOf(Tab.HOME to home, Tab.RECORDS to records, Tab.SETTINGS to settings), { selected }, { selected = it })

  @Test
  fun each_tab_keeps_where_it_was_left() {
    tabs.press(Tab.SETTINGS)
    tabs.current += "volume"
    tabs.press(Tab.HOME)
    assertEquals(listOf("home"), tabs.current)
    tabs.press(Tab.SETTINGS)
    assertEquals(listOf("settings", "volume"), tabs.current)
  }

  @Test
  fun pressing_the_shown_tab_goes_back_to_its_first_screen() {
    tabs.press(Tab.SETTINGS)
    tabs.current += "volume"
    tabs.current += "recordings"
    tabs.press(Tab.SETTINGS)
    assertEquals(listOf("settings"), tabs.current)
    assertEquals(Tab.SETTINGS, selected)
  }

  @Test
  fun back_goes_back_within_the_tab_then_to_home_then_leaves_the_app() {
    tabs.press(Tab.SETTINGS)
    tabs.current += "volume"
    assertFalse(tabs.onAnotherTabsFirst)
    assertTrue(tabs.back())
    assertEquals(listOf("settings"), tabs.current)
    assertTrue(tabs.onAnotherTabsFirst)
    assertTrue(tabs.back())
    assertEquals(Tab.HOME, selected)
    assertFalse(tabs.onAnotherTabsFirst)
    assertFalse(tabs.back())
    assertEquals(Tab.HOME, selected)
  }

  // Android #43: a second tap before the screen has moved does not stack the same screen twice.
  @Test
  fun pushing_the_screen_already_on_top_adds_nothing() {
    tabs.press(Tab.SETTINGS)
    tabs.push("volume")
    tabs.push("volume")
    assertEquals(listOf("settings", "volume"), tabs.current)
    tabs.push("user")
    assertEquals(listOf("settings", "volume", "user"), tabs.current)
  }

  @Test
  fun opening_a_screen_of_another_tab_shows_it_above_that_tabs_first_screen() {
    tabs.press(Tab.SETTINGS)
    tabs.current += "volume"
    tabs.press(Tab.HOME)
    tabs.open(Tab.SETTINGS, "user")
    assertEquals(Tab.SETTINGS, selected)
    assertEquals(listOf("settings", "user"), tabs.current)
    tabs.back()
    assertEquals(listOf("settings"), tabs.current)
  }

  @Test
  fun the_tabs_hide_while_an_ask_is_on_screen() {
    assertTrue(tabsShown(Phase.IDLE))
    for (phase in Phase.entries - Phase.IDLE) assertFalse(phase.name, tabsShown(phase))
  }

  // Android #57: SC-19 opened from Home lands on Home, not on a tab the rider moved to while its place was read; and a
  // second press while the first one's memo is on top adds nothing.
  @Test
  fun a_screen_is_pushed_only_on_its_tab_and_not_over_one_like_it() {
    tabs.pushOn(Tab.HOME, "note-1") { it.startsWith("note") }
    tabs.pushOn(Tab.HOME, "note-2") { it.startsWith("note") }
    assertEquals(listOf("home", "note-1"), home)
    tabs.press(Tab.RECORDS)
    tabs.pushOn(Tab.HOME, "note-3") { false }
    assertEquals(listOf("records"), records)
    assertEquals(listOf("home", "note-1"), home)
  }
}
