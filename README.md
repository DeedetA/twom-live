# TWOM Live

A **read-only** Android app that shows, live, what The World of Magic's game server sends to
your phone: mobs around you (type, level, position), mobs already fighting other players,
deaths and who killed them, gold dropped and picked up, chat.
It also shows what your phone sends to the server: your attacks (with the targeted mob),
your skills, and with "Details" your movements and technical messages.

The app never sends anything to the game and changes no data: it relays TWOM's
connections as they are and reads the game server stream along the way.

## Install

1. Open the repository's **Releases** page and download `TWOM-Live.apk` (latest).
2. Open the file on your phone. Android will ask you to allow installs from your
   browser or file manager: allow it for that source.

## Usage

1. Open **TWOM Live** and tap **Start**. Accept the VPN connection request
   (a key icon appears in the status bar).
2. Tap **Open TWOM**. If the game was already open, close it completely and restart it:
   a connection opened before the capture cannot be read.
3. Come back to TWOM Live to see the mobs in view and the log.

- **Chat**: show or hide chat messages.
- **Sent**: show what your phone sends (attacks, skills).
- **Details**: adds mob movements, hits, players in view and diagnostics.
- **Log colors**: green = received from the server, blue = sent by your phone,
  red = drops (gold dropped and picked up), grey = app messages.
- **Share**: sends the full log plus the events as CSV.
- **Mob alert**: type a name (or several, comma-separated: `Imp, Spector`). Case is ignored
  and part of the name is enough. When a matching entity spawns or comes into view, the app
  alerts you once with its ID, coordinates, and its distance and direction from you
  (e.g. "300 north-east", north = top of the screen), by **Notification**, **Vibration** and/or
  **Voice** (your choice). The name and choices are remembered. **Test** tries the alert.
  Alerts appear in orange in the log.

Only TWOM goes through the local VPN; the rest of the phone is not affected.

## Limits

- The server only sends what is close to your character: the app does not see the whole map.
- The protocol was worked out from November 2026 captures; a game update may change it.
- IPv4 only.

## How it works

- `TwomVpnService`: local VPN limited to TWOM (`addAllowedApplication`).
- `Relay`: small TCP stack that terminates connections on the phone and reopens them to
  the real servers, plus a UDP relay (DNS).
- `TwomDecoder`: decryption (XOR + rotation, per-frame key) and message parsing in both
  directions. Phone side: `0x43` movement, `0x4f` attack/skill (header + ~2 KB padding),
  `0x68` ping, `0xa8` UI actions, `0x27` login info.
- `GameState`: entity tracking and log.

The tests (`app/src/test`) replay real captures through the decoder and the relay.
Each push to `main` runs the tests, builds the APK and publishes it under Releases.
