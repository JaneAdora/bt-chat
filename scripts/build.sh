#!/usr/bin/env bash
# Builds a signed release APK. Keystore password comes from 1Password at build time.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
if [ ! -f keystore/release.jks ]; then
  echo "keystore/release.jks missing. Restore it from 1Password: op document get 'BT Chat release.jks' --vault=Dev --out-file keystore/release.jks" >&2
  exit 1
fi
BTCHAT_KEYSTORE_PASSWORD="$(op read 'op://Dev/BT Chat keystore/password')" ./gradlew assembleRelease
echo
ls -la app/build/outputs/apk/release/app-release.apk
