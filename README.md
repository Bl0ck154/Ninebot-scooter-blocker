<div align="center">

# 🛴 Ninebot Scooter Blocker

### Lightweight Android companion for Ninebot / Segway scooters

**Fast BLE reconnect · Live telemetry · One-tap lock · Persistent notification · Full-charge alert**

[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Release](https://img.shields.io/github/v/release/Bl0ck154/Ninebot-scooter-blocker?label=release)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)
[![Build](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml/badge.svg)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml)
[![Platform](https://img.shields.io/badge/platform-Android-blue)](https://github.com/Bl0ck154/Ninebot-scooter-blocker)

[**⬇️ Download latest APK**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)

</div>

---

## What is it?

**Ninebot Scooter Blocker** is a small daily-use Android companion built around the BLE/authentication implementation that is hardware-tested on the **Ninebot Max G30**.

The goal is simple: keep the useful everyday stuff from a big scooter utility app, without turning the project into a firmware flashing toolbox.

Open the app, let it reconnect to your scooter, see live telemetry, and lock or unlock it from the dashboard or directly from the Android notification.

### Built for everyday riding

| | Feature |
|---|---|
| 🔗 | Automatic BLE connection to the remembered scooter |
| 🔄 | Reconnect with battery-friendly backoff |
| 🔐 | One-tap software lock / unlock |
| 🔋 | Live battery %, voltage, current and power |
| 🛞 | Speed, trip, odometer and estimated remaining range |
| 🌡️ | Controller and battery temperatures when available |
| 📱 | Compact Android dashboard |
| 🔔 | Persistent live notification with an always-visible lock button |
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
6. Enable **Persistent notification** if you want background connection and quick lock control.

After the scooter reaches `READY`, the live notification appears automatically. If the connection is lost, the notification disappears and reconnect logic takes over.

> **Android 13+** requires notification permission for the live notification and full-charge alerts.

---

## Dashboard

The dashboard is intentionally small and focused:

- current scooter/model and BLE connection state;
- battery percentage as the main value;
- voltage, current and calculated power;
- speed and trip distance;
- remaining range and total odometer;
- controller / battery temperatures;
- one large `🔐 LOCK` / `🔓 UNLOCK` button;
- toggles for persistent notification, auto connect and full-charge alert.

No firmware menus, no tuning pages and no unrelated scooter-hacking controls.

---

## Live notification

When enabled and the scooter is connected, the app shows a compact ongoing notification similar to:

```text
🛴 Ninebot Max G30 · 98%
🟢 Connected · 63.7 km range · 0.0 km trip     [ 🔐 LOCK ]
```

The lock control is part of the collapsed notification itself, so you do not need to expand the notification just to lock or unlock the scooter.

The notification is silent, ongoing and uses a dedicated high-importance channel. Android/OEM ultimately controls exact notification ranking.

---

## Full-charge sound alert

Enable **Full-charge sound alert** if you want the phone to notify you when the monitored scooter reaches 100% while charging.

- Uses a separate Android notification channel: **Full charge alerts**.
- Sound and vibration can be configured from Android's own notification settings.
- The alert fires once when the observed battery level rises to 100%.
- It does not fire immediately just because the app starts while the scooter is already at 100%.

Because Android requires an active foreground connection for reliable background monitoring, enabling the full-charge alert also enables the persistent connection. Turning persistent notification off disables the charge alert too.

---

## Compatibility

### ✅ Hardware-tested baseline

**Ninebot Max G30**

The current project keeps the known-working G30 BLE transport, SHU-compatible authentication path and lock/unlock packet flow as the compatibility baseline.

### 🧪 Experimental compatibility

Discovery also accepts compatible Ninebot / Segway BLE devices instead of hard-coding a single advertised name. Some older scooters share the same legacy Proto2 register family and may work with the existing transport.

However, this is **not SHU-level universal compatibility**.

Newer Segway/Ninebot families may use Encryption2/Encryption3 authentication, different board routing or different register layouts. Models such as G2/F-series/etc. are **not claimed as tested** until they are actually verified on hardware.

Unknown devices are shown with a generic model label instead of pretending they are a G30.

---

## Telemetry

The central telemetry model can expose:

| Value | Source / behavior |
|---|---|
| Battery % | ESC |
| Voltage | BMS |
| Current | BMS |
| Power | calculated from voltage × current |
| Speed | ESC |
| Trip | ESC |
| Odometer | ESC |
| Remaining range | ESC |
| Controller temperature | ESC |
| Battery temperature | BMS |
| Lock state | read-only Ninebot status register |

Unsupported or missing values stay empty instead of being fabricated.

Telemetry polling is serialized through the BLE write queue. Fast values are refreshed more often while slower/static values are interleaved less frequently.

---

## BLE & reconnect behavior

Connection states:

```text
DISCONNECTED → SCANNING → CONNECTING → CONNECTED → READY
                                      ↘ RECONNECTING / ERROR
```

Reconnect uses backoff instead of hammering the radio:

```text
1 s → 2 s → 5 s → 10 s → 30 s
```

The app remembers the selected scooter and verifies its authenticated identity when possible, reducing the chance of automatically connecting to a random nearby Ninebot.

---

## Architecture

```text
ScooterBleManager
       ↓
NinebotBleClient
       ↓
   G30Protocol
       ↓
ScooterRepository
      ↙  ↘
MainActivity  ScooterService
```

### Responsibilities

- **`NinebotBleClient`** — Nordic UART GATT transport, crypto/authentication, 20-byte BLE fragmentation and serialized writes.
- **`ScooterBleManager`** — scanning and connection coordination.
- **`G30Protocol`** — Ninebot packet/register knowledge and telemetry parsing.
- **`ScooterRepository`** — single source of truth for connection state, identity, reconnect, telemetry and lock commands.
- **`ScooterService`** — foreground lifetime, live notification and full-charge alert.
- **`MainActivity`** — dashboard only; it does not own `BluetoothGatt`.

This separation is intentional: the working G30 transport remains isolated from UI and Android lifecycle code.

---

## Android permissions

The app asks only for permissions required by the Android version:

- **Android 12+** — `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`
- **Android 13+** — `POST_NOTIFICATIONS`
- **Android 11 and older** — location permission only because legacy Android BLE scanning requires it
- foreground connection uses the `connectedDevice` service type

The app does **not** request location on Android 12+.

---

## Battery usage

The app is intended to stay connected for long riding sessions, so background behavior is deliberately conservative:

- no permanent CPU wake lock;
- no endless aggressive scan;
- serialized GATT writes;
- adaptive telemetry polling;
- throttled notification updates;
- reconnect backoff.

If a manufacturer-specific battery optimizer kills the background service, the app provides a shortcut to the relevant Android battery settings.

---

## What this project deliberately does NOT do

Ninebot Scooter Blocker is **not** a firmware modification utility.

It does not include:

- firmware flashing;
- region changing;
- serial changing;
- motor power tuning;
- speed-limit modification.

Telemetry requests are read-only. Lock/unlock uses the existing working scooter command path.

---

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.9

Build locally:

```bash
gradle :app:assembleDebug
```

Every push to `main` runs GitHub Actions with:

1. Android lint;
2. unit tests;
3. debug APK build;
4. APK signature verification;
5. signed release build when release secrets are configured;
6. workflow artifact upload;
7. GitHub Release publication.

---

## Releases

Current release: **v0.9.1**

➡️ [**Download the latest APK**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)

---

## Protocol references

Development and protocol verification were informed by public Ninebot/Xiaomi reverse-engineering work and documentation, including:

- [Ninebot-PROTOCOL](https://github.com/ub4raf/Ninebot-PROTOCOL)
- [py9b](https://github.com/etransport/py9b)
- [ninebot-ble](https://github.com/ownbee/ninebot-ble)
- [M365-Rokid-HUD](https://github.com/zero2005x/M365-Rokid-HUD) — Android architecture reference, not a replacement for the G30 protocol layer
- [Segway / Ninebot BLE documentation](https://nootnooot.codeberg.page/segway-ninebot-ble/)

The project's own working G30 implementation remains the primary compatibility source.

---

<div align="center">

**Built for a simple job: connect, monitor, lock, ride.**

</div>
