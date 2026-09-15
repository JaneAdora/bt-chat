# BT Chat: offline two-phone messaging over Bluetooth Classic

Date: 2026-09-15
Status: approved design, pending implementation plan
Research: `docs/research-2026-09-15.md`

## 1. Purpose

A parent and their kid each carry a Samsung Galaxy S23-class phone. The kid's phone has no SIM and no data plan. They want to send each other quick texts and photos when they are near each other (same house, same office, same store) with no internet, no cellular, and no third-party service. Existing apps (BitChat, Briar, Knit) are either unreliable on Samsung, in maintenance mode, or built on BLE and not modifiable enough to trust.

The app is Android-only, Kotlin, Jetpack Compose, and uses Bluetooth Classic RFCOMM between phones that have been paired once in system settings. It is fully owned and modifiable by Jane.

## 2. Goals and non-goals

### Goals (v1)

- Text messages with emoji between two bonded Android phones, with sent, delivered, and read status.
- Photos, downscaled before sending so a transfer takes a second or two.
- Works with the app closed: a foreground service holds or re-establishes the link and posts notifications with inline reply. A "Stay connected" toggle turns that off.
- Reconnects automatically when the phones come back into range, including after sleep, reboot, or Bluetooth being toggled.
- Never loses a message. Out-of-range messages queue and send when the link returns.
- Light and dark theme, following the system by default with a manual override.
- Built and installed from the terminal on Linux. No Android Studio.

### Non-goals (v1)

- More than one contact in the UI (schema and protocol support it, see section 12).
- Desktop or Linux client (v1.5, see section 12).
- Mesh relaying, group chat, voice, video, full-resolution photos, message editing or deletion sync, typing indicators.
- Encryption beyond the Bluetooth link layer. Bonded Classic links are encrypted by the controller; that is enough for a parent and kid in one house. An application-layer cipher is a v2 decision.
- iOS. Ever.

## 3. Platform and constraints

- Devices: Samsung Galaxy S23 and Galaxy S23 FE. Same One UI generation, same Samsung Bluetooth stack, Bluetooth 5.3.
- minSdk 34 (Android 14). Both phones are past it. targetSdk and compileSdk are the current stable at implementation time, pinned in the Gradle version catalog.
- Transport: Bluetooth Classic RFCOMM (Serial Port Profile). Not BLE. Reasons: stream socket semantics, no MTU fragmentation, one-time pairing then silent reconnect, 150 to 200 KB/s in practice, and no iOS constraint pushing us to BLE.
- Range: about 10 m indoors. Walls reduce it. Not a design problem; the queue covers gaps.
- Bluetooth cannot be emulated. All link testing happens on the two real phones.

## 4. Architecture

Five units, one package each. Dependencies point downward only. The UI never touches Bluetooth.

```
ui        Compose screens. Reads Store, sends commands to Service.
service   Foreground service. Hosts Link, drains the outbound queue, posts notifications.
protocol  Frames <-> messages. Pure Kotlin, no Android imports.
link      Bluetooth socket ownership: listen, dial, tie-break, reconnect. Exposes state + streams.
store     Room database + DataStore settings + photo files on disk.
```

### 4.1 Link

- Registers an RFCOMM listener on the service UUID `a558fe46-ab4a-43c3-b323-820b0da29a31` using `listenUsingInsecureRfcommWithServiceRecord`. Insecure here means no re-authentication prompt; the link is still encrypted because both phones are bonded.
- Dials the saved peer with `createInsecureRfcommSocketToServiceRecord` on the same UUID.
- Both phones listen and dial at all times while the service runs (symmetric). Whichever socket opens first is used.
- Tie-break when two live sockets exist to the same peer: each side compares its own adapter address to the peer's. The side with the lower address keeps the socket it dialed and closes the accepted one; the higher side does the opposite. Both apply the rule, so exactly one socket survives.
- Dial backoff: 2 s, 5 s, 15 s, 30 s, then every 30 s. Resets on any successful connection.
- Keepalive: PING every 20 s when idle, expect PONG within 10 s, otherwise treat as disconnected. RFCOMM does not always notice a dropped link promptly.
- Exposes: `state: StateFlow<LinkState>` (Off, Searching, Connected(peerAddress)), `inbound: Flow<ByteArray>` of raw bytes, `send(bytes)` which suspends until the bytes are written or throws on disconnect.
- Knows nothing about messages or frames.

### 4.2 Protocol

Pure Kotlin. A `FrameCodec` turns `Frame` objects into bytes and a `FrameReader` reassembles frames from an arbitrary byte stream.

Header, 16 bytes, big-endian:

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | version | 1 |
| 1 | 1 | type | see table |
| 2 | 2 | reserved | 0 |
| 4 | 4 | body length | max 2 MiB; larger closes the socket |
| 8 | 8 | message id | random, sender-generated; 0 for control frames without one |

Frame types:

| Type | Name | Body | Id |
|---|---|---|---|
| 0x01 | HELLO | JSON `{proto, nick, color, app}` | 0 |
| 0x02 | TEXT | JSON `{ts, text}` | message id |
| 0x03 | PHOTO | 2-byte meta length, meta JSON `{ts, w, h, mime}`, then JPEG bytes | message id |
| 0x04 | ACK | empty | id of the received message |
| 0x05 | READ | empty | id of the message that was read |
| 0x06 | PING | empty | 0 |
| 0x07 | PONG | empty | 0 |

- HELLO is sent by both sides immediately after a socket is established. It carries the sender's nickname and accent color. The receiver stores them on the peer row. `proto` is the protocol version; a mismatch shows a "please update" message and disconnects.
- Unknown types are skipped by length. Bad version, bad reserved bytes, or oversized length close the socket.
- Message ids are 64-bit random values generated by the sender. The receiver dedupes on `(peer, id)`.

### 4.3 Store

Room database with two tables plus DataStore for settings.

`peers`

| Column | Type | Notes |
|---|---|---|
| address | text PK | Bluetooth adapter address |
| nick | text | from HELLO, falls back to the bonded device name |
| color | text | hex, from HELLO |
| last_seen | long | epoch ms of last connection |

`messages`

| Column | Type | Notes |
|---|---|---|
| id | long PK | sender-generated, 64-bit random |
| peer | text | FK to peers.address |
| direction | int | 0 outbound, 1 inbound |
| kind | int | 0 text, 1 photo |
| text | text nullable | |
| photo_path | text nullable | app-private file |
| thumb_path | text nullable | app-private file |
| created_at | long | sender's timestamp |
| status | int | 0 queued, 1 sent, 2 delivered, 3 read (outbound); 1 received, 3 read (inbound) |
| delivered_at | long nullable | |
| read_at | long nullable | |

Unique index on `(peer, id)`.

Settings (DataStore): `my_nick`, `my_color`, `theme` (system, light, dark), `stay_connected`, `send_read_receipts`, `active_peer` (address).

Photos live under the app's private files dir. Received photos are additionally written to the device gallery via MediaStore in a "BT Chat" album so they survive an uninstall.

### 4.4 Service

`ChatService`, a foreground service of type `connectedDevice`.

- Started by: the app opening, the boot receiver when `stay_connected` is on, the Bluetooth adapter-on broadcast when `stay_connected` is on, and the notification toggle.
- Stopped by: turning `stay_connected` off (the service stops when the app is backgrounded), or the notification action.
- Owns one `Link` for the active peer. Observes `messages` for queued outbound rows and sends them in `created_at` order. Marks sent when `send` returns, delivered on ACK, read on READ.
- On inbound TEXT or PHOTO: dedupe, insert row, write the photo file, send ACK, post a notification unless the chat screen for that peer is visible.
- On inbound ACK or READ: update status.
- On disconnect: any outbound row in status sent but not delivered returns to queued. Resumes listening and dialing.
- Sends READ for a message when the UI reports it displayed (see 4.5) and `send_read_receipts` is on. READ frames for messages that arrived while the link was down are sent on the next connection.

### 4.5 UI

Compose, Material 3, single activity. Three surfaces.

Chat screen (the main screen):
- Status strip at the top: "Connected to {nick}", "Looking for {nick}...", "Bluetooth is off" with a button to the system toggle, or "Stay connected is off" with a switch. Peer nick comes from `peers.nick`.
- Message list, newest at the bottom. Outbound on the right in my color, inbound on the left in the peer's color. Large readable type. Emoji render at text size. Timestamps in a muted monospace, grouped by day.
- Photos as thumbnails with a fixed aspect box; tap opens a full-screen viewer with pinch zoom and a save action.
- Status mark under outbound messages: clock for queued, one check for sent, two for delivered, two filled for read.
- Composer: text field, photo button (opens the system photo picker, no storage permission needed) and camera button (capture intent), send button. Send is enabled while the link is down; the message just queues.
- When the list is at the bottom and the screen is on, newly visible inbound messages are reported as read.

Setup screen (first launch, or when the saved peer is missing):
- Nickname, accent color from a small fixed palette.
- List of bonded Bluetooth devices from the adapter; pick one. Button to open system Bluetooth settings if the other phone is not paired yet.
- Explains and requests permissions: Nearby devices (BLUETOOTH_CONNECT), Notifications, and the battery optimization exemption with a one-line reason.

Settings sheet:
- Theme: system, light, dark.
- Stay connected toggle.
- Send read receipts toggle.
- Edit nickname and color (re-sent in the next HELLO).
- Change peer (returns to the device picker).
- Clear history.

Visual rules: no gradients, no thick colored left borders, no centered emoji hero. Full thin borders or tints only. High contrast in light mode for outdoor use.

### 4.6 Notifications

Two channels.

- `service` (low importance, silent): the persistent notification. Title is the link state using the peer's nick. One action: "Stay connected" toggle.
- `messages` (high importance): one notification per peer, updated in place, with the latest text or a photo thumbnail. Inline reply action; a reply from the notification is queued like any other outbound message and counts as read for the messages shown. Tapping opens the chat screen. Suppressed while the chat screen for that peer is in the foreground.

## 5. Data flow

Outbound text: UI inserts a row with status queued. Service observes the new row, encodes a TEXT frame, calls `Link.send`. Status becomes sent when the write completes, delivered when an ACK with that id arrives, read when a READ with that id arrives.

Outbound photo: UI decodes the picked image off the main thread, scales the long edge to 1280 px, encodes JPEG at quality 80, writes the file and a 256 px thumbnail, inserts a row. Same path as text from there. Typical size 150 to 300 KB, one to two seconds on the link.

Inbound: Link yields bytes, `FrameReader` assembles a frame, Service handles it by type as described in 4.4. UI observes the `messages` table and redraws.

Nothing in the UI blocks on Bluetooth.

## 6. Connection lifecycle

1. Service starts. Checks adapter state. If off, state is Off and the service waits for the adapter-on broadcast.
2. Registers the listener. Starts the dial loop toward `active_peer`.
3. On any socket: apply tie-break if a socket already exists, send HELLO, start the keepalive, drain the queue.
4. On HELLO received: update the peer row, update the notification title.
5. On disconnect (read error, write error, keepalive timeout): close the socket, mark in-flight messages queued, go to Searching, restart the dial loop from 2 s.
6. On stop: close everything, remove the persistent notification.

Pairing happens once in system Bluetooth settings. The app never enters discoverable mode and never triggers a pairing dialog after setup.

## 7. Error handling

The app never loses a message and never shows a stack trace.

- Link drops mid-transfer: the in-flight outbound message returns to queued and is resent whole. The receiver discards any partial frame.
- Lost ACK: sender resends; receiver sees a duplicate `(peer, id)`, does not insert, re-sends ACK.
- Bluetooth off: status strip says so with a button to the system toggle; the service idles.
- Saved peer no longer bonded: the app drops to the setup screen with a one-line explanation.
- Permission denied: the setup screen explains why and re-asks. Nothing fails silently.
- Malformed frame or oversized length: close the socket and reconnect.
- Photo decode failure: a toast, the message is not created.
- Storage full: the message is not created, a toast explains.
- Battery optimization not exempted: the app still works while open; the status strip shows a persistent hint until the exemption is granted.

## 8. Permissions

- `BLUETOOTH_CONNECT` (runtime, "Nearby devices"). Required to list bonded devices and open sockets.
- `POST_NOTIFICATIONS` (runtime).
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- `RECEIVE_BOOT_COMPLETED`.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (prompts once).
- No `BLUETOOTH_SCAN`: the app never discovers, it only connects to bonded devices.
- No storage permission: the photo picker and MediaStore insert do not need one.

## 9. Testing

Unit tests (JVM, no device):
- `FrameCodec` round-trips for every frame type, including empty bodies and a 2 MiB photo body.
- `FrameReader` fed one byte at a time, in random chunk sizes, and with garbage prefixes; asserts correct frames and correct socket-close signals for bad headers.
- Queue drain order, status transitions, dedupe on duplicate id, in-flight rollback on disconnect. Link is replaced by a fake with an in-memory pipe.
- Tie-break rule is a pure function with a table test.
- Photo downscale produces a long edge of 1280 and a file under 400 KB for a sample 12 MP input.

Manual device checklist (run on the two S23s before any release build is called done):
1. Fresh install both, pair in settings, complete setup, see Connected on both.
2. Text each way, see delivered and read marks.
3. Photo each way, thumbnail and full view, file in gallery.
4. Airplane mode on one phone for 30 s then off: reconnects within 30 s, queued messages arrive.
5. Both phones screen off for 10 minutes: send a text from one, the other buzzes.
6. Walk out of range, send three messages, walk back: all three arrive in order.
7. Reboot the kid's phone with Stay connected on: reconnects with no interaction.
8. Stay connected off: service stops when the app is backgrounded; on again restarts it.
9. Toggle light and dark; check outdoor readability.
10. Inline reply from a notification arrives and marks the shown messages read.

## 10. Build and install

Kotlin, Jetpack Compose, Gradle Kotlin DSL, version catalog. No Android Studio.

- `scripts/setup-sdk.sh`: installs JDK 17 and the Android command-line tools under `~/android-sdk`, accepts licenses, installs one platform and one build-tools version, writes `local.properties`. Idempotent.
- `scripts/build.sh`: `./gradlew assembleRelease`, signed with `keystore/release.jks`. The keystore password is read from 1Password with `op` at build time and never written to disk. `keystore/` is gitignored. The keystore file is also stored in 1Password as a document, because losing it means every install must be wiped to update.
- `scripts/install.sh <device>`: `adb -s <device> install -r` the release APK. Devices are reached over wireless debugging (Developer options, pair once per phone). Debug builds are never installed on the phones, so signatures never conflict.
- Application id: `dev.jane.btchat`. This cannot change after the first install without a wipe.
- App name: "BT Chat".

## 11. Repository layout

```
bt-chat/
  app/                      Android module
    src/main/kotlin/dev/jane/btchat/
      link/  protocol/  store/  service/  ui/
    src/test/kotlin/...     JVM unit tests
  scripts/                  setup-sdk.sh, build.sh, install.sh
  docs/                     research, specs, plans
  keystore/                 gitignored
```

## 12. Roadmap after v1

v1.5:
- Contact list in the phone UI: multiple peers, one Link per peer, chats keyed by peer. The schema and protocol already support this.
- Linux desktop client: registers the same UUID with BlueZ over D-Bus (`Profile1`), speaks the same frames, terminal or small window UI. This machine has a Bluetooth 5.4 adapter and is a bonded peer like any phone.
- Protocol document extracted from this spec into `docs/protocol.md` so a second implementation has a single source of truth.

v2 candidates, each its own decision:
- Application-layer encryption with a key exchanged at setup.
- Send original photo on request.
- Short voice clips (the stream makes them cheap).
- Typing indicator.
- Wi-Fi Direct as a second transport for range and speed.

## 13. Decisions log

- Bluetooth Classic over BLE: stream semantics, silent reconnect, no iOS pressure. BitChat's flakiness is largely BLE connection churn.
- Symmetric listen and dial over fixed roles: faster reconnect, no per-phone configuration.
- Insecure RFCOMM variants: skip the re-authentication prompt; bonded links are still encrypted.
- Peer-aware schema and protocol in v1 even with a single-peer UI: avoids a migration and a protocol bump for v1.5.
- Photos downscaled to 1280 px: keeps transfers to a second or two and makes whole-message resend acceptable.
- Read receipts in v1 with a per-phone toggle: one frame type, one status value.
- Stay connected toggle rather than always-on: the pager behavior is the point, the switch saves battery on demand.
