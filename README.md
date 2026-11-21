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

- **Drops** (red): gold and items that fall when a mob dies, with the mob they came from
  ("item #150198 dropped at (438,2763) (from Yeti)"), pick-ups, and "You obtained item #..."
  when an item reaches your inventory. The server sends item numbers, not names.
- **Items used**: "You use item #130069 (bag 3, slot 5)" (blue), and the server's inventory
  updates: "Inventory bag 3, slot 5: item #130069 x9" or "now empty".
- **NPCs and gold**: "You talk to NPC #1170", "You sell item #150193 x1 (bag 1, slot 5) to
  NPC #1170", "NPC #1170 offers 500 gold", "You buy article #20 from NPC #1158",
  "You choose option 404 (NPC #1178)", and every change of your gold ("Gold +500", "Gold -495").
- **Your character**: damage you take ("Yeti Highguard hits you for 6", in orange), HP and MP when
  the server sends them (potions: "HP: 933", "MP: 164"), and EXP per kill ("+7 EXP (total
  3,513,818)"). Skill crystal gains are shown with Details ("Skill crystals +0.5").
- **Status line** (top of the mobs panel): "Crystals ~4.5 / 5   HP 933   MP 164". The server sends
  every crystal gain but never what your skills spend, so set each skill's cost in the **Table** tab. The estimate corrects itself: it never goes over 5, and casting a
  skill proves you had at least its cost. Costs are set in the Table tab (Skills). HP and MP are the last values the server sent (map
  change, potion): the game computes damage and regeneration on the phone.
- **Mob HP**: read from each mob's data when it comes into view (current and max), then reduced by
  every hit anyone lands on it. Shown in the mobs panel ("Yeti Highguard (2336,2079) HP 1541/1650"),
  with Details in the log, and as max HP in Mob alerts (handy when a boss shares a normal mob's
  sprite). Mob regeneration is not sent, so HP is re-synced each time the server resends the mob.
- **Buffs, teleports, equipment**: "Buff #32 started (20 min)", "Buff #132 started (1 min) x3"
  (stacks after kills), "Move speed: 130", "Teleporting to Wingfield Beach (map #5)",
  "You equip item #30010" / "You unequip item #40029".
- **Three tabs**: **Settings** (what to show in the log, Mob alert and how to be alerted), **Log**
  (status line, mobs in view and everything that happens) and **Table**.
- **Table**: a spreadsheet of everything the game only sends as numbers — skills, stats, buffs,
  maps, items and NPCs. Every number seen while playing is added automatically (unnamed rows in
  yellow, with the last value and a note of what is already known). Type a name and it is used in
  the log ("Power Strike (skill #7)", "Crit rate: 5820"); for skills also their crystal and mana
  costs, which feed the crystal counter. **Share table** sends it as CSV.
- **Inventory**: read from the inventory pages sent at each map change, then kept up to date slot
  by slot. The inventory has 12 base slots; each bag takes one of them and adds its own slots, so
  usable slots = 12 + bag sizes - number of bags (10 bags of 7 and 2 of 8 = 86). Shown as
  "Bag 82/86" on the status line. Bag sizes are in Table > Bags. **Inventory alert**: notification /
  vibration / voice when the free slots drop to the number you set (default 3).
- **Players in view**: level, class, guild, side (Enemy = the other faction, Ally = yours; in the
  village everyone shows as Ally), PK status (White, Purple, Red, Black) and HP, nearest first.
  **Enemy player alert**: alert once when a player of the other faction comes into view, by
  default only if they are a player killer (purple, red or black PK).
- **Moves** (checkbox): every movement around you — who moves, from where to where, how far and how
  fast, facing which way ("Roopa #9694a34d (1007,1546) -> (1007,1525), moved 21 in 0.64 s, facing
  north, stopped").
- **Map loading**: "Map loading..." / "Map loaded" at login and after every teleport.
- **Chat**: show or hide chat messages, including what you write ("You say: hi").
- **Sent**: show what your phone sends (attacks, skills).
- **Details**: adds hits, players in view and diagnostics.
- **Raw**: logs every message in both directions (`<-` received, `->` sent) with its type,
  size and decrypted content in hex, and records it. The recording is made for long sessions:
  it is split into 16 MB pieces and keeps up to 400 MB (more than 30 hours on a very busy map,
  about 12 MB per hour); past that, only the oldest pieces are dropped. It stops if less than
  200 MB is free on the phone. **Raw file** joins everything into one `twom_raw.txt`, zips it
  (about 7 times smaller) and opens the share menu. **Clear** deletes the recording.
  Two things are never shown or recorded: the content of the login message (it may hold your
  session token) and the ~2 KB anti-cheat block attached to each attack (only its size).
  Type names ending with "?" were inferred from captures and are not certain yet.
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
  `0x68` ping, `0xa4` use item, `0x91` NPC dialog, `0x9e` chat, `0xa8` UI actions, `0x27` login info.
  Server side: `0x8c` gold change, `0x91` NPC replies, `0x87` inventory slot, `0x86` item obtained.
- `GameState`: entity tracking and log.

The tests (`app/src/test`) replay real captures through the decoder and the relay.
Each push to `main` runs the tests, builds the APK and publishes it under Releases.
