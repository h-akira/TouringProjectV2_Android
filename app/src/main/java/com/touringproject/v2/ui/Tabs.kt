package com.touringproject.v2.ui

import com.touringproject.v2.ask.Phase

// The tabs and their back stacks (slice 2 spec §7.1), kept free of Compose so they are tested on the JVM.

enum class Tab { HOME, RECORDS, SETTINGS }

/**
 * Each tab has its own back stack: plain mutable lists (Navigation 3's NavBackStack in the app). The selected tab is
 * read and written through the given functions, so the screen can keep it in saveable state.
 */
class TabStacks<K>(
  private val stacks: Map<Tab, MutableList<K>>,
  private val selected: () -> Tab,
  private val select: (Tab) -> Unit,
) {
  val tab: Tab get() = selected()

  val current: MutableList<K> get() = stacks.getValue(selected())

  /** On the first screen of Records or Settings, where Back goes to Home rather than within the tab. */
  val onAnotherTabsFirst: Boolean get() = current.size == 1 && tab != Tab.HOME

  /** Another tab shows as it was left; the tab already shown goes back to its first screen. */
  fun press(pressed: Tab) {
    if (pressed == tab) toFirst(current) else select(pressed)
  }

  /**
   * Back within the tab; from another tab's first screen, to Home. Returns false on Home's first screen, where Back is
   * left to the system (Android #32).
   */
  fun back(): Boolean {
    val stack = current
    if (stack.size > 1) {
      stack.removeAt(stack.lastIndex)
      return true
    }
    if (tab == Tab.HOME) return false
    select(Tab.HOME)
    return true
  }

  /** Shows key above the first screen of tab, e.g. the user name from Home's user chip. */
  fun open(tab: Tab, key: K) {
    val stack = stacks.getValue(tab)
    toFirst(stack)
    stack += key
    select(tab)
  }

  private fun toFirst(stack: MutableList<K>) {
    while (stack.size > 1) stack.removeAt(stack.lastIndex)
  }
}

/** The ask screen covers everything while it is shown, and the tabs go with it (slice 2 spec §7.1). */
fun tabsShown(phase: Phase): Boolean = phase == Phase.IDLE
