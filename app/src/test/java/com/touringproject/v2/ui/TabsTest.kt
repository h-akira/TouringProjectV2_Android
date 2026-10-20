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
    assertTrue(tabs.canGoBack)
    assertTrue(tabs.back())
    assertEquals(listOf("settings"), tabs.current)
    assertTrue(tabs.back())
    assertEquals(Tab.HOME, selected)
    assertFalse(tabs.canGoBack)
    assertFalse(tabs.back())
    assertEquals(Tab.HOME, selected)
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
}
