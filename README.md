<div align="center">

# 🛴 Ninebot Scooter Blocker

### Lightweight Android companion for Ninebot / Segway scooters

**Fast BLE reconnect · Live telemetry · Ride statistics · One-tap lock · Persistent notification · Full-charge alert**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Release](https://img.shields.io/github/v/release/Bl0ck154/Ninebot-scooter-blocker?label=release)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)
[![Build](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml/badge.svg)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml)
[![Platform](https://img.shields.io/badge/platform-Android-blue)](https://github.com/Bl0ck154/Ninebot-scooter-blocker)

[**⬇️ Download latest APK**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)

</div>

---

## What is it?

**Ninebot Scooter Blocker** is a small daily-use Android companion built around the BLE/authentication implementation that is hardware-tested on the **Ninebot Max G30**.

It keeps the everyday features useful during long courier shifts without becoming a firmware flashing/tuning toolbox: reliable background BLE, telemetry, lock/unlock, a compact notification, charge alerts and local ride statistics.

### Built for everyday riding

| | Feature |
|---|---|
| 🔗 | Automatic BLE connection to the remembered scooter |
| 🔄 | Reconnect with battery-friendly backoff |
| 🔐 | One-tap software lock / unlock |
| 🔋 | Live battery %, voltage, current and power |
| 🛞 | Speed, odometer and estimated remaining range |
| 📊 | Persistent ride + day/week/month statistics |
| ⏸️ | Short scooter sleeps stay inside the same ride session |
| 💾 | JSON statistics import / export |
| 🌡️ | Controller and battery temperatures when available |
| 🔔 | Persistent live notification with ride distance and quick lock control |
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

The main dashboard stays intentionally compact:

- current scooter/model and BLE state;
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

Statistics are stored locally on the phone and separated by the remembered scooter serial number (BLE address is the fallback identity).

### Current ride

A ride session tracks:

- distance from **odometer deltas** rather than the G30 trip counter;
- elapsed session duration;
- connected time;
- battery percentage discharged;
- battery percentage charged;
- maximum observed speed.

Turning the scooter off for a shop/restaurant stop does not immediately finish the ride. The same ride continues when the scooter reconnects inside the configured pause window.

The pause timeout is selectable in the Statistics screen:

```text
20 min · 30 min · 60 min
```

The same timeout controls how long the disconnected/reconnecting live notification stays visible.

If a pause goes past the timeout, the ride closes automatically. **Continue previous** can reopen the last ride within 24 hours when the newly created ride has not accumulated movement yet. **End ride** closes the current session manually.

### Day / week / month

The Statistics screen aggregates:

- distance;
- ride count;
- connected time;
- max speed;
- battery % used;
- battery % charged.

Recent individual rides are listed underneath the period summary.

### Backup

**Export JSON** creates a portable backup of the local ride database. **Import JSON** replaces local statistics from such a backup; scooter pairing/settings are not replaced.

---

## Live notification

When connected, the compact notification keeps a stable field order. Example:

```text
🛴 Ninebot Max G30 · 73% · 12.4 km
27.3 km/h · 31.0 km range · 🟢 Connected     [ 🔐 LOCK ]
```

If the scooter sleeps or disconnects, the same ride distance is retained and the notification switches to reconnect state instead of disappearing immediately:

```text
🛴 Ninebot Max G30 · 73% · 12.4 km
31.0 km range · 🔴 Reconnecting               [ ↻ RECONNECT ]
```

It remains available for the configured ride pause timeout (20/30/60 minutes). A successful reconnect restores the live lock/unlock button and continues the same statistical ride. If the timeout expires, the notification is removed and the ride is closed.

The live channel is silent/high-importance; exact notification ranking is ultimately controlled by Android/OEM firmware.

---

## Full-charge sound alert

Enable **Full-charge sound alert** if you want the phone to notify you when the monitored scooter reaches 100% while charging.

- Separate Android channel: **Full charge alerts**.
- Sound/vibration can be configured in Android notification settings.
- Fires once when the observed battery level rises to 100%.
- Does not fire immediately just because the app starts with a scooter already at 100%.

Reliable background charge monitoring needs the foreground connection, so enabling the charge alert also enables persistent notification. Turning persistent notification off disables the charge alert too.

---

## Compatibility

### ✅ Hardware-tested baseline

**Ninebot Max G30**

The project keeps the known-working G30 BLE transport, SHU-compatible authentication path and lock/unlock packet flow as its compatibility baseline.

### 🧪 Experimental compatibility

Discovery also accepts compatible Ninebot / Segway BLE devices instead of hard-coding a single advertised name. Some older scooters share the same legacy Proto2 register family and may work with the existing transport.

This is **not SHU-level universal compatibility**. Newer families can use Encryption2/Encryption3 authentication, different board routing or different register layouts. Models are not claimed as tested until verified on hardware.

---

## Telemetry

| Value | Source / behavior |
|---|---|
| Battery % | ESC |
| Voltage | BMS |
| Current | BMS |
| Power | calculated from voltage × current |
| Speed | ESC |
| Scooter trip | ESC |
| Odometer | ESC |
| Remaining range | ESC |
| Controller temperature | ESC |
| Battery temperature | BMS |
| Lock state | read-only Ninebot status register |
| App ride/session | persisted from odometer deltas |

Unsupported values stay empty instead of being fabricated. BLE writes stay serialized and slower telemetry is interleaved with faster values.

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

The remembered G30 is tried directly by its saved BLE address before scan fallback. A watchdog resets stuck Android GATT attempts. Authenticated identity is verified when the serial is available.

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
- **`G30Protocol`** — Ninebot packet/register knowledge and telemetry parsing.
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

## Battery usage

Designed for multi-hour courier use:

- no permanent CPU wake lock;
- no endless aggressive scan;
- serialized GATT writes;
- adaptive telemetry polling;
- throttled notification refresh;
- statistics writes batched/throttled instead of writing on every BLE packet;
- reconnect backoff.

---

## What this project deliberately does NOT do

It does not include firmware flashing, region/serial changing, motor power tuning or speed-limit modification. Telemetry requests are read-only; lock/unlock uses the existing working scooter command path.

---

## Build

Requirements: **JDK 17 · Android SDK 35 · Gradle 8.9**

```bash
gradle :app:assembleDebug
```

Every push to `main` runs lint, unit tests, APK build, signature verification, artifact upload and GitHub Release publication.

---

## Releases

Current feature release: **v0.10.0**

➡️ [**Download the latest APK**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)

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
