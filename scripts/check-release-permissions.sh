#!/usr/bin/env bash
# Fails when the release APK asks for a permission that is not on the list below (migration spec §8.1).
# The last three come from libraries (Media3, Play services, AndroidX core) and are not dangerous permissions.
set -euo pipefail

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AAPT2="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)/aapt2"

expected="android.permission.ACCESS_COARSE_LOCATION
android.permission.ACCESS_FINE_LOCATION
android.permission.BLUETOOTH
android.permission.BLUETOOTH_CONNECT
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK
android.permission.FOREGROUND_SERVICE_MICROPHONE
android.permission.INTERNET
android.permission.MODIFY_AUDIO_SETTINGS
android.permission.POST_NOTIFICATIONS
android.permission.RECORD_AUDIO
android.permission.ACCESS_NETWORK_STATE
android.permission.WAKE_LOCK
com.touringproject.v2.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

actual=$("$AAPT2" dump permissions "$APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort)
extra=$(comm -13 <(echo "$expected" | sort) <(echo "$actual"))
if [ -n "$extra" ]; then
  echo "unexpected permissions in $APK:" >&2
  echo "$extra" >&2
  exit 1
fi
echo "release permissions OK ($(echo "$actual" | wc -l | tr -d ' '))"
