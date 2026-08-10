<div align="center">

# 🛴 Ninebot Scooter Blocker

### Lightweight Android companion for Ninebot / Segway scooters

**Fast BLE reconnect · Live telemetry · Ride sessions · Activity calendar · One-tap lock · Persistent notification · Full-charge alert**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Release](https://img.shields.io/github/v/release/Bl0ck154/Ninebot-scooter-blocker?label=release)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)
[![Build](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml/badge.svg)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml)

[**⬇️ Download latest APK**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)

</div>

---

## What is it?

**Ninebot Scooter Blocker** is a tiny daily-use Android companion built around the BLE/authentication implementation hardware-tested on the **Ninebot Max G30**.

It focuses on the things useful during everyday riding and courier work instead of becoming a firmware-tuning toolbox: reliable background BLE, live telemetry, quick software lock/unlock, a compact notification, charging alerts and persistent ride statistics.

| | Feature |
|---|---|
| 🔗 | Automatic BLE connection to the remembered scooter |
| 🔄 | Reconnect with battery-friendly backoff and stale-GATT protection |
| 🔐 | One-tap software lock / unlock |
| 🔋 | Battery %, voltage, current, power and charging detection |
| 📶 | Live BLE signal strength / RSSI bars |
| 🛞 | Live speed, odometer and estimated remaining range |
| 📍 | Movement-based ride sessions that survive short stops and reconnects |
| 📅 | Tappable 17-week GitHub-style distance activity calendar |
| 📊 | Day / Week / Month summaries with date navigation and ride history |
| 💾 | Local SQLite history with JSON statistics import / export |
| 🌡️ | Controller and battery temperatures when available |
| 🔔 | Persistent notification with current ride distance, signal and quick lock control |
| 🔊 | Optional short double-beep when a monitored charge reaches 100% |
| 🏠 | Home-screen lock shortcut |
| ⚡ | No permanent wake lock and no aggressive continuous BLE scanning |

---

## ✨ Statistics at a glance

The Statistics screen is built around a **GitHub-style activity calendar**. Every square is one day; heavier riding days get a stronger fill.

```text
       recent 17 weeks →
Mon   · ░ ░ ▒ ░ ▓ ▒ ░ · ░ █ ▓ ░ ▒ · ░ ▓
Tue   ░ ▒ ░ ░ ▓ █ ░ · ░ ▒ ▓ ░ ░ █ ▒ ░ ▓
Wed   · ░ ▒ ▓ ░ ▒ █ ░ · ░ ▒ ▓ ░ ▒ ░ █ ▓
Thu   ░ ░ ░ ▒ ▓ ░ ▒ █ ░ · ░ ▓ ▒ ░ ░ ▒ █
Fri   ▒ ▓ ░ ░ █ ▒ ░ ▓ ░ ▒ · ░ █ ▓ ▒ ░ ▓
Sat   █ ▓ ▒ ░ ▒ █ ▓ ░ ░ ▒ ▓ █ ░ ▒ ▓ █ ░
Sun   ▓ ░ · ░ ▒ ▓ █ ▒ ░ · ░ ▓ ▒ █ ░ ▒ ▓

      Less  ·  ░  ▒  ▓  █  More distance
```

Tap a day to inspect it. Switch between **Day / Week / Month**, move backward or forward through dates, and see distance, ride count, connected time, max speed, battery used/charged and the rides belonging to that period.

> The heatmap is relative to recent riding history, so it remains readable for both occasional rides and long courier shifts.

[**Read the full statistics documentation →**](docs/STATISTICS.md)

---

## Quick start

1. Download the latest APK from [**Releases**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest).
2. Install it on Android.
3. Allow Bluetooth permissions.
4. Select your scooter once.
5. Leave **Auto connect** enabled.
6. Enable **Persistent notification** if you want background connection, session continuity and quick lock control.

> **Android 13+** requires notification permission for the live notification and full-charge alert.

---

## Dashboard

The compact dashboard shows:

- detected scooter/model and BLE state;
- live BLE signal strength in bars and dBm;
- battery percentage, voltage, current and power;
- charging state when detected;
- live speed;
- **Session** distance tracked independently from the scooter's temporary trip counter;
- remaining range and total odometer;
- temperatures;
- one large `🔐 LOCK` / `🔓 UNLOCK` button;
- persistent notification, auto-connect and full-charge switches;
- direct entry into the statistics screen.

Swipe left from the dashboard or tap **Statistics ›**. The Statistics screen uses normal native vertical scrolling; use the visible back control or Android back gesture/button to return.

---

## Ride sessions

A ride is defined by **real movement**, not by Bluetooth uptime.

The app watches odometer deltas and speed. A session starts when movement is observed and remains open through short stops. If there is no real movement for the configured timeout, that ride closes automatically **even if BLE remains connected the entire time**.

Choose how long a short stop should still count as the same ride:

```text
20 min · 30 min · 60 min
```

That makes a quick shop/restaurant stop part of the same ride, while parking for several hours and riding again creates a new session automatically.

Distance is calculated from **total odometer deltas**, not the scooter's volatile trip register, so a scooter sleep/reconnect does not reset the app session. Fresh reconnect samples are treated as a baseline to avoid artificial distance jumps.

**End ride** closes a session immediately. **Continue previous** is available when two sessions should intentionally be joined.

Statistics are stored locally and separated by scooter serial number, with BLE MAC as the fallback identity.

---

## Day / Week / Month history

The redesigned Statistics screen provides:

- **Day** — inspect one selected calendar day;
- **Week** — Monday through Sunday around the selected date;
- **Month** — the whole calendar month;
- left/right date navigation;
- period-specific ride lists;
- distance, rides, connected time and max speed;
- observed battery percentage used and charged;
- a 17-week activity heatmap for fast visual browsing.

Ride samples stay live in memory and are persisted in small batches rather than writing SQLite on every telemetry packet.

**Export JSON** creates a portable local backup. **Import JSON** restores statistics without replacing scooter BLE settings.

---

## Live notification

The notification deliberately gives limited horizontal space to values useful while riding:

```text
🛴 73% · 12.4 km
27.3 km/h · 🟢 Connected                 [ 🔐 LOCK ]
```

The distance is the app's current ride/session, not the scooter's temporary trip counter. BLE signal bars are also shown in the custom notification layout.

If the scooter sleeps or disconnects, the current session value is retained while reconnecting:

```text
🛴 73% · 12.4 km
🔴 Reconnecting                          [ ↻ RECONNECT ]
```

The disconnected notification remains available for the configured inactivity/grace window. A successful reconnect refreshes the notification immediately instead of waiting for the normal telemetry refresh throttle.

---

## Charging & full-charge alert

Charging detection uses sustained BMS current while the scooter is stationary instead of trusting one isolated current sample. This avoids treating short regenerative-braking current as a charge session.

While charging, the UI/notification alternates a lightweight `🔋` / `⚡` indicator.

Enable **Full-charge sound alert** to get a dedicated notification when an observed charge reaches 100%. The app plays a short, low-key double-beep from the phone notification audio stream and fires the alert once per observed charge session.

Reliable background charge monitoring needs the foreground connection, so enabling the alert also keeps the persistent connection active.

---

## Compatibility

### ✅ Hardware-tested baseline

**Ninebot Max G30**

The known-working G30 BLE transport, SHU-compatible authentication and lock/unlock byte sequences are intentionally kept as the compatibility baseline.

### 🧪 Experimental compatibility

Discovery also accepts compatible Ninebot / Segway BLE devices instead of hard-coding one advertised name. Some older scooters share the same legacy Proto2 register family and may work with the current transport.

This is **not SHU-level universal compatibility**. Newer families can use Encryption2/Encryption3 authentication, different board routing or different register layouts. A model is not claimed as tested until it is verified on hardware.

---

## Telemetry

| Value | Source / behavior |
|---|---|
| Battery % | ESC |
| Voltage | BMS |
| Current | BMS |
| Power | calculated from voltage × current |
| Charging | conservative stationary-current detector |
| BLE signal | Android connected-GATT RSSI |
| Speed | ESC |
| Odometer | ESC |
| Remaining range | ESC |
| Controller temperature | ESC |
| Battery temperature | BMS |
| Lock state | read-only Ninebot status register |
| App ride/session | persisted from movement + odometer deltas |

Unsupported values stay empty instead of being fabricated. BLE writes and RSSI reads are serialized, and slower telemetry is interleaved with faster values.

---

## BLE & reconnect behavior

```text
DISCONNECTED → SCANNING → CONNECTING → CONNECTED → READY
                                      ↘ RECONNECTING / ERROR
```

Reconnect backoff:

```text
1 s → 2 s → 5 s → 10 s → 30 s
```

Most reconnect attempts use direct GATT to the remembered BLE address. Every third failed cycle can use an 8-second balanced scan fallback. Connection and scan generations protect a new session from late Android callbacks belonging to an old GATT/scan, and a watchdog resets stuck attempts.

`READY` and user-visible state transitions are serialized on the main queue so an older Bluetooth callback cannot downgrade an already authenticated connection back to `CONNECTED` / `RECONNECTING`.

Connected telemetry uses one small request per scheduler tick: roughly 1 request/second while stopped and up to 2/second while moving. RSSI is sampled much less frequently and uses the same serialized GATT path. There is no continuous BLE scan while connected.

---

## Architecture

```text
ScooterBleManager
       ↓
NinebotBleClient
       ↓
   G30Protocol
       ↓
ScooterRepository ─── RideStatsTracker ─── RideStatsStore / SQLite
      ↙   ↓   ↘                 ↓
MainActivity  StatsActivity  ScooterService
                  ↓
           RideHeatmapView
```

- **`NinebotBleClient`** — Nordic UART GATT transport, crypto/authentication, fragmentation, RSSI reads and serialized writes.
- **`ScooterBleManager`** — scanning and connection coordination with stale-callback generation guards.
- **`G30Protocol`** — register knowledge and telemetry parsing.
- **`ScooterRepository`** — connection, identity, reconnect, telemetry and commands.
- **`RideStatsTracker` / `RideStatsStore`** — movement-based ride lifecycle and local statistics database.
- **`RideHeatmapView`** — compact tappable 17-week distance calendar.
- **`ScooterService`** — foreground lifetime, live notification and full-charge alert.
- **`MainActivity` / `StatsActivity`** — presentation only; neither owns `BluetoothGatt`.

---

## Statistics database

Statistics schema v2 stores movement time separately from general telemetry activity. This fixes the old behavior where a parked scooter could keep one ride alive for hours simply because BLE remained connected.

Legacy open v1 rides are closed once during migration so an old telemetry timestamp cannot silently merge into the next real ride.

For implementation details, period semantics and backup behavior, see [**docs/STATISTICS.md**](docs/STATISTICS.md).

---

## Android permissions

- **Android 12+** — `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- **Android 13+** — `POST_NOTIFICATIONS`
- **Android 11 and older** — location only because legacy Android BLE scanning requires it
- foreground connection uses the `connectedDevice` service type

No location permission is requested on Android 12+.

---

## Battery and size

The project deliberately avoids heavyweight runtime dependencies: it uses Android framework Bluetooth, SQLite, JSON, drawing and UI APIs directly; JUnit exists only in the test configuration and is not packaged in the APK.

Published APKs are **release builds**, optimized by R8 with code shrinking/obfuscation and Android resource shrinking. CI also builds a debug APK and reports debug vs release size so accidental binary growth is visible.

Runtime work is kept small with no permanent wake lock, reconnect backoff, serialized GATT operations, adaptive polling, throttled notification refresh, batched statistics persistence and no endless aggressive scan.

---

## What this project deliberately does NOT do

It does not include firmware flashing, region/serial changing, motor power tuning or speed-limit modification. Telemetry requests are read-only; lock/unlock uses the existing working scooter command path.

---

## Build

Requirements: **JDK 17 · Android SDK 35 · Gradle 8.9**

```bash
gradle :app:assembleDebug
gradle :app:assembleRelease
```

Every push to `main` runs release lint, unit tests, debug + optimized release builds, signature verification, size reporting, artifact upload and GitHub Release publication.

---

## Protocol references

- [Ninebot-PROTOCOL](https://github.com/ub4raf/Ninebot-PROTOCOL)
- [py9b](https://github.com/etransport/py9b)
- [ninebot-ble](https://github.com/ownbee/ninebot-ble)
- [M365-Rokid-HUD](https://github.com/zero2005x/M365-Rokid-HUD) — Android architecture reference
- [Segway / Ninebot BLE documentation](https://nootnooot.codeberg.page/segway-ninebot-ble/)

The project's own working G30 implementation remains the primary compatibility source.

---

<div align="center">

**Connect · monitor · lock · ride · remember the shift.**

</div>
