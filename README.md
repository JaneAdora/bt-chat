# BT Chat

Two Android phones, no internet, no cell service. Text and photos over Bluetooth Classic.
Design: `docs/superpowers/specs/2026-09-15-bt-chat-design.md`.

## Build from the terminal

1. `scripts/setup-sdk.sh` once. Installs JDK 17, the Android command-line SDK, and the Gradle wrapper.
2. `scripts/build.sh` for a signed release APK. Reads the keystore password from 1Password.
3. `./gradlew testDebugUnitTest` for the unit tests.

The keystore lives in `keystore/release.jks` (gitignored). If it is missing:
`op document get 'BT Chat release.jks' --vault=Dev --out-file keystore/release.jks`.
Every install must be signed with this key or Android refuses the update.
The application id `dev.jane.btchat` can never change after the first install either; changing the id or the keystore means uninstalling on both phones and losing history.

## Install on a phone over Wi-Fi (once per phone)

1. On the phone: Settings, About phone, Software information, tap Build number seven times.
2. Settings, Developer options, turn on Wireless debugging.
3. Tap Wireless debugging, then "Pair device with pairing code". Note the IP:port and code.
4. On this machine: `~/android-sdk/platform-tools/adb pair <ip:port>` and enter the code.
5. Back on the phone, the Wireless debugging screen shows a different IP:port. Run
   `~/android-sdk/platform-tools/adb connect <ip:port>`.
6. `~/android-sdk/platform-tools/adb devices` lists the phone. Use that serial with
   `scripts/install.sh <serial>`.

The connect step (5) is needed again after a reboot or a network change; pairing (4) is not.
If Wireless debugging is off on the kid's phone, copy `app-release.apk` over any way you like
and tap it; allow installs from that source once.

## First run

Pair the two phones in system Bluetooth settings once. Open BT Chat on both, allow Nearby devices
and Notifications, turn off battery optimization, pick a name and color, choose the other phone,
tap Done. The status strip goes to "Connected to <name>" within a few seconds.

## Logs

`~/android-sdk/platform-tools/adb -s <serial> logcat -s BluetoothLink:V ChatService:V AndroidRuntime:E`
