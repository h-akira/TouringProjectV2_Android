import java.time.Duration
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// The API URL lives in local.properties (not tracked): trv2.apiBaseUrl=https://<id>.execute-api.ap-northeast-1.amazonaws.com/Prod
val localProps = Properties().apply {
  val file = rootProject.file("local.properties")
  if (file.exists()) file.inputStream().use { load(it) }
}
val apiBaseUrl: String = localProps.getProperty("trv2.apiBaseUrl") ?: ""

android {
  namespace = "com.touringproject.v2"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.touringproject.v2"
    minSdk = 29
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions {
    unitTests.isIncludeAndroidResources = true
  }
  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  testImplementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // JVM tests: plain JUnit, Robolectric for Android classes, Compose UI tests under Robolectric
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)

  // Emulator tests
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
}

// A test that deadlocks on the main thread must fail the build instead of hanging it.
tasks.withType<Test>().configureEach {
  timeout.set(Duration.ofMinutes(5))
}
