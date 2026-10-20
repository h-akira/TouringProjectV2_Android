package com.touringproject.v2.ui

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp

// The tabs of the app (02 §1, slice 2 spec §7.1). The icons are text, so no icon library is added.

private val TAB_LABELS = listOf(Tab.HOME to ("🏠" to "ホーム"), Tab.RECORDS to ("🗺" to "記録"), Tab.SETTINGS to ("⚙" to "設定"))

@Composable
fun TabBar(selected: Tab, onPress: (Tab) -> Unit) {
  NavigationBar {
    TAB_LABELS.forEach { (tab, look) ->
      NavigationBarItem(
        selected = tab == selected,
        onClick = { onPress(tab) },
        icon = { Text(look.first, fontSize = 20.sp) },
        label = { Text(look.second) },
        modifier = Modifier.testTag("tab-${tab.name}"),
      )
    }
  }
}
