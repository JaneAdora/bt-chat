#!/usr/bin/env bash
# Installs the release APK on one phone over adb.
# Usage: scripts/install.sh <adb-serial>     (list serials with: adb devices)
set -euo pipefail
cd "$(dirname "$0")/.."
ADB="${ANDROID_SDK_ROOT:-$HOME/android-sdk}/platform-tools/adb"
SERIAL="${1:?usage: scripts/install.sh <adb-serial>}"
APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "no release APK; run scripts/build.sh first" >&2; exit 1; }
"$ADB" -s "$SERIAL" install -r "$APK"
"$ADB" -s "$SERIAL" shell monkey -p dev.jane.btchat -c android.intent.category.LAUNCHER 1 >/dev/null
