package com.touringproject.v2.nav

import android.content.Context
import android.content.Intent
import android.util.Log
import com.touringproject.v2.ask.NavReturn
import com.touringproject.v2.settings.LaunchableApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

class AppLauncher(private val context: Context) : NavReturn {
  // Opens the nav app with its launcher intent, which resumes the existing task (the route survives).
  // startActivity may be called from any thread, so this does not switch to the main thread.
  override suspend fun returnTo(packageName: String): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      context.startActivity(intent)
      true
    } catch (e: Exception) {
      Log.w("trv2.ride", "failed to launch $packageName", e)
      false
    }
  }

  suspend fun launchableApps(): List<LaunchableApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val collator = Collator.getInstance(Locale.JAPANESE)
    pm.queryIntentActivities(intent, 0)
      .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
      .distinctBy { it.packageName }
      .filter { it.packageName != context.packageName }
      .sortedWith(compareBy(collator) { it.label })
  }
}
