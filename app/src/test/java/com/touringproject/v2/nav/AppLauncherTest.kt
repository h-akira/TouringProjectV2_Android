package com.touringproject.v2.nav

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.touringproject.v2.settings.LaunchableApp
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AppLauncherTest {
  private val app = ApplicationProvider.getApplicationContext<Application>()

  private fun installLauncherApp(packageName: String, label: String) {
    val pm = shadowOf(app.packageManager)
    val component = ComponentName(packageName, "$packageName.Main")
    pm.addActivityIfNotPresent(component)
    pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    pm.getInternalMutablePackageInfo(packageName).applicationInfo!!.nonLocalizedLabel = label
    pm.getInternalMutablePackageInfo(packageName).activities!!.first().nonLocalizedLabel = label
  }

  @Before
  fun setUp() {
    installLauncherApp("com.google.android.apps.maps", "マップ")
    installLauncherApp("jp.co.yahoo.carnavi", "カーナビ")
  }

  @Test
  fun returning_opens_the_nav_app_with_its_launcher_intent() = runTest {
    assertTrue(AppLauncher(app).returnTo("com.google.android.apps.maps"))
    val started = shadowOf(app).nextStartedActivity
    assertEquals("com.google.android.apps.maps", started.component?.packageName)
    assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun returning_to_an_app_that_is_not_installed_fails_quietly() = runTest {
    assertFalse(AppLauncher(app).returnTo("not.installed"))
  }

  @Test
  fun launchable_apps_are_listed_without_this_app() = runTest {
    val apps = AppLauncher(app).launchableApps()
    assertTrue(LaunchableApp("com.google.android.apps.maps", "マップ") in apps)
    assertTrue(apps.none { it.packageName == app.packageName })
  }
}
