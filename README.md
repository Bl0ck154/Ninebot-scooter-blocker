<div align="center">

# 🛴 Ninebot Scooter Blocker

### Lightweight Android companion for Ninebot / Segway scooters

**Fast BLE reconnect · Live telemetry · Ride statistics · One-tap lock · Persistent notification · Full-charge alert**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Release](https://img.shields.io/github/v/release/Bl0ck154/Ninebot-scooter-blocker?label=release)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)
[![Build](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml/badge.svg)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml)

[**⬇️ Download latest APK**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)

</div>

---

## What is it?

**Ninebot Scooter Blocker** is a small daily-use Android companion built around the BLE/authentication implementation hardware-tested on the **Ninebot Max G30**.

It focuses on the things useful during everyday riding and courier work instead of becoming a firmware-tuning toolbox: reliable background BLE, telemetry, quick software lock/unlock, a compact notification, charge alerts and persistent ride statistics.

| | Feature |
|---|---|
| 🔗 | Automatic BLE connection to the remembered scooter |
| 🔄 | Reconnect with battery-friendly backoff and stale-GATT protection |
| 🔐 | One-tap software lock / unlock |
| 🔋 | Battery %, voltage, current and power |
| 🛞 | Live speed, odometer and estimated remaining range |
| 📊 | Persistent ride + day/week/month statistics |
| ⏸️ | Short scooter sleeps stay inside the same ride session |
| 💾 | JSON statistics import / export |
| 🌡️ | Controller and battery temperatures when available |
| 🔔 | Persistent notification with current ride distance and quick lock control |
| 🔊 | Optional sound alert when charging reaches 100% |
| 🏠 | Home-screen lock shortcut |
| ⚡ | No permanent wake lock and no aggressive continuous BLE scanning |

---

## Quick start

1. Download the latest APK from [**Releases**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest).
2. Install it on Android.
3. Allow Bluetooth permissions.
4. Select your scooter once.
5. Leave **Auto connect** enabled.
6. Enable **Persistent notification** if you want background connection, session continuity and quick lock control.

> **Android 13+** requires notification permission for the live notification and full-charge alerts.

---

## Dashboard

The compact dashboard shows:

- detected scooter/model and BLE state;
- battery percentage, voltage, current and power;
- live speed;
- **Session** distance tracked independently from the scooter's volatile trip counter;
- remaining range and total odometer;
- temperatures;
- one large `🔐 LOCK` / `🔓 UNLOCK` button;
- persistent notification, auto-connect and full-charge switches;
- direct entry into the statistics screen.

Swipe left from the dashboard or tap **Statistics ›**. Swipe right on the statistics screen to return.

---

## Ride statistics

Statistics are stored locally on the phone and separated by scooter serial number, with BLE address as the fallback identity.

A current ride tracks distance from **odometer deltas**, elapsed time, connected time, battery percentage used/charged and maximum observed speed. Turning the scooter off for a short shop/restaurant stop does not immediately finish the ride.

The pause timeout is selectable:

```text
20 min · 30 min · 60 min
```

The same timeout controls how long the disconnected/reconnecting notification stays visible. If the timeout is exceeded, the ride closes automatically. **Continue previous** can reopen the last ride within 24 hours when the newly created ride has not accumulated movement; **End ride** closes it manually.

The Statistics screen provides **Day / Week / Month** summaries plus recent rides. **Export JSON** creates a portable local backup and **Import JSON** restores it without replacing scooter connection settings.

Ride samples stay live in memory and are persisted in small batches rather than writing SQLite on every telemetry packet.

---

## Live notification

The notification deliberately gives the limited horizontal space to the values useful while working:

```text
🛴 73% · 12.4 km
27.3 km/h · 🟢 Connected                 [ 🔐 LOCK ]
```

The distance is the app's current ride/session, not the scooter's temporary trip counter.

If the scooter sleeps or disconnects, the session value is retained:

```text
🛴 73% · 12.4 km
🔴 Reconnecting                          [ ↻ RECONNECT ]
```

It remains available for the configured ride pause timeout. A successful reconnect restores the lock button and continues the same statistical ride.

---

## Full-charge sound alert

Enable **Full-charge sound alert** to notify the phone when the monitored scooter reaches 100% while charging.

- separate Android notification channel;
- sound/vibration configurable in Android settings;
- fires once when an observed charge rise reaches 100%;
- does not fire merely because the app starts while the battery is already at 100%.

Reliable background charge monitoring needs the foreground connection, so enabling this alert also enables persistent notification.

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
| Speed | ESC |
| Odometer | ESC |
| Remaining range | ESC |
| Controller temperature | ESC |
| Battery temperature | BMS |
| Lock state | read-only Ninebot status register |
| App ride/session | persisted from odometer deltas |

Unsupported values stay empty instead of being fabricated. BLE writes are serialized and slower telemetry is interleaved with faster values.

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

Connected telemetry uses one small request per scheduler tick: roughly 1 request/second while stopped and up to 2/second while moving. There is no continuous scan while connected.

---

## Architecture

```text
ScooterBleManager
       ↓
NinebotBleClient
       ↓
   G30Protocol
       ↓
ScooterRepository ─── RideStatsTracker ─── SQLite
      ↙   ↓   ↘
MainActivity  StatsActivity  ScooterService
```

- **`NinebotBleClient`** — Nordic UART GATT transport, crypto/authentication, fragmentation and serialized writes.
- **`ScooterBleManager`** — scanning and connection coordination.
- **`G30Protocol`** — register knowledge and telemetry parsing.
- **`ScooterRepository`** — connection, identity, reconnect, telemetry and commands.
- **`RideStatsTracker` / `RideStatsStore`** — ride continuity and local statistics database.
- **`ScooterService`** — foreground lifetime, live notification and full-charge alert.
- **`MainActivity` / `StatsActivity`** — presentation only; neither owns `BluetoothGatt`.

---

## Android permissions

- **Android 12+** — `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- **Android 13+** — `POST_NOTIFICATIONS`
- **Android 11 and older** — location only because legacy Android BLE scanning requires it
- foreground connection uses the `connectedDevice` service type

No location permission is requested on Android 12+.

---

## Battery and size

The project deliberately avoids heavyweight runtime dependencies: it uses Android framework Bluetooth, SQLite, JSON and UI APIs directly; JUnit exists only in the test configuration and is not packaged in the APK.

Published APKs are **release builds**, optimized by R8 with code shrinking/obfuscation and Android resource shrinking. CI also builds a debug APK and reports debug vs release size so accidental binary growth is visible.

Runtime work is kept small with no permanent wake lock, reconnect backoff, serialized GATT writes, adaptive polling, throttled notification refresh, batched statistics persistence and no endless aggressive scan.

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
