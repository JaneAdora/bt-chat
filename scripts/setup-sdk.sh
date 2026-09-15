#!/usr/bin/env bash
# Installs everything needed to build BT Chat from the terminal. Idempotent.
set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/android-sdk}"
CLT_ZIP="commandlinetools-linux-15859902_latest.zip"
CLT_URL="https://dl.google.com/android/repository/${CLT_ZIP}"
GRADLE_VERSION="9.7.1"
JDK_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

if [ ! -x "$JDK_HOME/bin/java" ]; then
  echo "Installing OpenJDK 17"
  sudo apt-get update && sudo apt-get install -y openjdk-17-jdk
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$SDK/cmdline-tools"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Downloading Android command-line tools"
  tmp="$(mktemp -d)"
  curl -fL "$CLT_URL" -o "$tmp/$CLT_ZIP"
  unzip -q "$tmp/$CLT_ZIP" -d "$tmp"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
fi
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"

yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$SDK" "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

if [ ! -x "$SDK/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  echo "Downloading Gradle $GRADLE_VERSION"
  tmp="$(mktemp -d)"
  curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$tmp/gradle.zip"
  unzip -q "$tmp/gradle.zip" -d "$SDK"
  rm -rf "$tmp"
fi

echo "sdk.dir=$SDK" > "$PROJECT_DIR/local.properties"

mkdir -p "$HOME/.gradle"
if ! grep -q '^org.gradle.java.home=' "$HOME/.gradle/gradle.properties" 2>/dev/null; then
  echo "org.gradle.java.home=$JDK_HOME" >> "$HOME/.gradle/gradle.properties"
fi

if [ ! -x "$PROJECT_DIR/gradlew" ]; then
  # Generate the wrapper in an empty scratch directory, not in the project.
  # Running "gradle wrapper" inside the project evaluates settings.gradle.kts
  # and build.gradle.kts, which resolves every plugin before the wrapper
  # task runs; an empty directory has nothing to resolve and stays fast.
  wrapper_dir="$(mktemp -d)"
  echo 'rootProject.name = "wrapper-gen"' > "$wrapper_dir/settings.gradle.kts"
  (cd "$wrapper_dir" && "$SDK/gradle-$GRADLE_VERSION/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --distribution-type bin --offline)
  cp "$wrapper_dir/gradlew" "$wrapper_dir/gradlew.bat" "$PROJECT_DIR/"
  mkdir -p "$PROJECT_DIR/gradle/wrapper"
  cp "$wrapper_dir/gradle/wrapper/"* "$PROJECT_DIR/gradle/wrapper/"
  chmod +x "$PROJECT_DIR/gradlew"
  rm -rf "$wrapper_dir"
fi

echo "Done. SDK at $SDK. Build with: ./gradlew assembleDebug"
