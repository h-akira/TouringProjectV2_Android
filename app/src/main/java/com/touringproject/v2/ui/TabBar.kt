package com.touringproject.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

/** The Records tab until slice 2 plan 4 puts the list of rides and days (SC-05) here. */
@Composable
fun RecordsPlaceholder(modifier: Modifier = Modifier) {
  Column(modifier.fillMaxSize().padding(16.dp)) {
    Text("走行と日ごとの記録の一覧は、次の版でここに出ます。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
  }
}
