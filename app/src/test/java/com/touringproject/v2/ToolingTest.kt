package com.touringproject.v2

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

// Proves the JVM test setup (Robolectric on SDK 35 with JDK 17) works before anything else is built on it.
@RunWith(AndroidJUnit4::class)
class ToolingTest {
  @Test
  fun robolectric_runs_with_the_app_package() {
    assertEquals("com.touringproject.v2", ApplicationProvider.getApplicationContext<Context>().packageName)
  }
}
