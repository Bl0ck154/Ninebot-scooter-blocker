<div align="center">

# 🛴 Ninebot Scooter Blocker

### Free Android companion for Ninebot / Segway scooters — tested on Max G30

**One-tap software lock · Persistent Bluetooth connection · Live notification · Ride history · Charge alerts**

Built by a **Ninebot Max G30 owner** for riders who want a quick software lock, useful live notification and ride history without opening a full scooter app. Independent, free and open source. Connects directly via Bluetooth, with no account, cloud or firmware flashing.

**✅ Tested on one Ninebot Max G30 · 🧪 Other Ninebot / Segway models: community testers wanted.** Some related models might work, but none are verified yet. Even a report that a model *doesn't* connect is useful.

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![Latest release](https://img.shields.io/github/v/release/Bl0ck154/Ninebot-scooter-blocker?label=latest%20APK)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest)
[![Build](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml/badge.svg)](https://github.com/Bl0ck154/Ninebot-scooter-blocker/actions/workflows/android.yml)
[![MIT license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

### [⬇️ Download Android APK](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest) · [Installation guide](docs/INSTALL.md) · [Compatibility](docs/COMPATIBILITY.md)

<img src="docs/assets/app-preview.svg" alt="Illustrated Ninebot Scooter Blocker dashboard and ride statistics screens. Sample values, not an actual screenshot." width="100%">

*Illustrative UI overview with sample data — not screenshots from a device. [Real screenshot capture guide](docs/SCREENSHOT_GUIDE.md).*

</div>

## Why I built it

I wanted something simple for my own **Ninebot Max G30**: keep my phone connected, lock the scooter quickly, and see what's going on without opening a full app every time. That's how Ninebot Scooter Blocker started.

Imagine leaving your scooter for a quick stop. You can **lock it directly from the Android notification** before putting your phone away. While riding, the same notification shows battery level, ride distance, speed and connection status. When charging, it shows the charging state too. No need to keep switching back to the app.

This isn't a complex tuning tool. It's a small, practical companion for everyday riding — originally built around the Ninebot Max G30, with the hope of learning which other Ninebot / Segway models can work too.

| Everyday need | What the app provides |
| --- | --- |
| 🔐 **Quick software lock** | Lock or unlock on the dashboard, directly from the **Android notification**, or via an optional home-screen shortcut |
| 🔗 **Keep your connection** | A persistent authenticated Bluetooth connection with automatic reconnect attempts to your remembered scooter |
| 🔋 **Battery at a glance** | Live battery percentage, charging status, ride distance, speed and signal in the notification |
| 🚶 **Walk away without immediately losing the status** | The disconnected notification retains the last known battery reading for **up to 30 minutes**, with a **RECONNECT** button |
| 🔔 **Know when a monitored charge finishes** | Optional phone notification and a short sound when the battery reaches 100% |
| 📊 **Remember your rides** | Movement-based sessions, 17-week activity calendar, day/week/month statistics and local JSON backups |

Built for everyday riding and courier work — not for flashing firmware, changing speed limits or tuning motor power.

## Will it work with your Ninebot / Segway?

**Have another Ninebot or Segway scooter? I'd love your help finding out.** Device discovery accepts several Ninebot/Segway Bluetooth naming patterns, but detecting a device is not proof that its authentication, telemetry or software lock commands are compatible.

| Scooter models | Current status |
| --- | --- |
| **Ninebot Max G30** | ✅ **Hardware-tested** on my single personally owned scooter: BLE connection, telemetry, lock/unlock |
| **G30-family variants** (for example G30LP, G30D, G30E) | 🧪 **Not tested by this project** — especially interesting candidates for community reports, not promised to work |
| **ES / E / F and other Ninebot / Segway series** | 🧪 **Not tested** — different protocols and firmware may need code changes |
| **Max G2 / G3, F2 and newer families** | 🧪 **Not tested** — some use different authentication/encryption or register layouts; do not assume compatibility |

**Even partial test results are valuable:** Can the app discover your scooter? Can it authenticate? Does battery percentage or speed show correctly? Where does it fail? You don't need to test lock/unlock to contribute a useful report.

**[🧪 Report your model's compatibility](https://github.com/Bl0ck154/Ninebot-scooter-blocker/issues/new?template=compatibility_report.yml)** · [Read the safe testing guide](docs/COMPATIBILITY.md) · [Contribute a fix](https://github.com/Bl0ck154/Ninebot-scooter-blocker/pulls)

This is still a **one-scooter-tested project**. Switching between different scooters and reconnecting after switching devices have not been thoroughly tested. Please never share serial numbers or BLE MAC addresses in public reports. Only test controls on a scooter you own or are authorized to operate, while safely stationary.

## Get started

1. Open [**Latest Releases**](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest) and download the Android `.apk`.
2. Install the APK on an **Android 8.0+** phone with Bluetooth LE.
3. Grant Bluetooth permissions, then select your scooter once.
4. Turn on **Auto connect** for faster reconnects.
5. Enable **Persistent notification** for background connection and quick access to the lock action. Android 13+ also needs notification permission.

> This project is distributed as a GitHub APK, not through Google Play. Download from this repository's official Releases page rather than an unknown APK mirror. **New to APK installation?** Follow the [step-by-step setup and troubleshooting guide](docs/INSTALL.md).

## A little extra protection — not an anti-theft system

Let's be clear: **this app cannot prevent someone from stealing your scooter.** A determined thief could still pick it up and carry it away. It does not replace a good physical lock, and it is not a GPS tracker, alarm or remotely enforced immobilizer.

Think of it as **an extra layer of protection against opportunistic ride-offs** — the kind of person who sees an unattended scooter and tries to hop on and ride away. With the G30's built-in **software lock** enabled, riding it away normally becomes harder. For a quick stop, that's genuinely useful. For longer stops, **use a physical lock as well**.

There's a second layer: **keeping your own Bluetooth connection active**. On the G30, the control connection is normally occupied by one client at a time. While your phone keeps its authenticated BLE session connected, this can make it harder for another nearby phone or app to grab the same control connection.

That is **not a guarantee against all other connections**, nor a cryptographic security feature. Your phone can lose Bluetooth range, Android may restrict background activity, and the scooter may turn off or reconnect differently. The app simply tries to reconnect to your remembered scooter when it can.

## The Android notification is the best everyday shortcut

The **persistent notification** is one of my favourite parts of the app. You don't need to open the dashboard just to check the scooter or lock it.

**While connected**, it displays:
- the latest **battery percentage** and **current ride distance**;
- **speed** and Bluetooth connection/signal status;
- a **charging indicator** when charging is detected;
- a one-tap **LOCK / UNLOCK** action.

Example (illustrative values):

```text
🛴 73% · 12.4 km
27.3 km/h · 🟢 Connected               [ 🔐 LOCK ]
```

**When you walk away** and Bluetooth disconnects, the notification doesn't immediately disappear. It keeps showing the **last known battery percentage** and ride distance, so you can quickly check what your scooter showed when you left it. Instead of the lock action, it offers **RECONNECT**.

```text
🛴 73% · 12.4 km
🔴 Reconnecting                          [ ↻ RECONNECT ]
```

**The offline notification lasts for up to 30 minutes after disconnection**, not indefinitely, and can be dismissed. The saved battery percentage is **the last reading, not a live update** while you're away. When the connection returns, the live status and lock action return too.

The optional **full-charge sound alert** can also notify you when a monitored charging session actually reaches 100%. It needs an active monitored connection; it cannot detect a completed charge from out of Bluetooth range.

## Ride tracking without a cloud account

The app records sessions from scooter **movement and odometer changes**, not GPS. Short stops can remain in one session (20, 30 or 60 minute split), while longer inactivity starts a new ride. Browse a tappable **17-week activity calendar**, daily/weekly/monthly summaries and ride history.

Statistics are stored on your phone in a local database and can be exported/imported as JSON. There is **no GPS route map** and no cloud account requirement.

## Frequently asked questions

<details>
<summary><strong>Can I lock a Ninebot Max G30 from my Android phone?</strong></summary>

Yes. Ninebot Scooter Blocker has hardware-tested software lock/unlock on the Ninebot Max G30 through an authenticated Bluetooth connection. It is a supplement to, not a replacement for, a physical lock.

</details>

<details>
<summary><strong>Could this work with a Ninebot G30LP, G30D, G2, F2, ES or another Segway scooter?</strong></summary>

Possibly — but **none of those models has been verified with this app**. G30-related variants are especially interesting to test, while other models may need different authentication or telemetry implementations. Seeing a scooter in the scan does not prove compatibility. If you own another model, [share a partial or complete test report](https://github.com/Bl0ck154/Ninebot-scooter-blocker/issues/new?template=compatibility_report.yml).

</details>

<details>
<summary><strong>Does it track location or require GPS?</strong></summary>

No. Ride distance and sessions come from scooter telemetry and odometer deltas, not a GPS route tracker.

</details>

<details>
<summary><strong>Does it need internet, an account or a subscription?</strong></summary>

No account or subscription is needed. The app communicates with your scooter locally over Bluetooth and stores its ride statistics on your phone.

</details>

<details>
<summary><strong>Will it reconnect while my phone is locked?</strong></summary>

It can attempt background reconnection when the persistent notification/connection feature is enabled. Results depend on Android battery optimizations, Bluetooth range and scooter availability. Keeping a connection active may reduce opportunities for competing BLE control sessions on the tested G30, but it cannot guarantee that nobody else will ever connect.

</details>

<details>
<summary><strong>Does the notification stay visible when I leave my scooter behind?</strong></summary>

Yes, for **up to 30 minutes** after disconnecting, unless you dismiss it. It shows the **last known** battery percentage and ride distance, not current readings from out of range. The action changes from **LOCK / UNLOCK** to **RECONNECT** until the scooter connects again.

</details>

<details>
<summary><strong>Does it protect my Ninebot from theft?</strong></summary>

Only as a secondary deterrent against casually riding off. The built-in software lock is not a replacement for a physical scooter lock; someone can still carry the scooter away. The persistent BLE connection is not a guaranteed block on other phones.

</details>

<details>
<summary><strong>Can I use it with several scooters?</strong></summary>

The app lets you change the remembered scooter, but development and hands-on testing used **one personally owned Ninebot Max G30**. Switching among different devices and reconnecting after these changes has not been comprehensively tested. Reports and patches from other owners are welcome.

</details>

<details>
<summary><strong>Can it unlock speed limits, flash firmware or tune motor power?</strong></summary>

No. This is a daily-use monitoring, ride statistics and software-lock companion, not a firmware-tuning application.

</details>

<details>
<summary><strong>Is this an official Segway / Ninebot app?</strong></summary>

No. It is an independent open-source project, unaffiliated with Segway-Ninebot.

</details>

## Privacy and permissions

The app does not need an account or cloud service. Android 12+ uses Bluetooth permissions; Android 13+ needs notification permission for the live status and charging alert. Older Android versions require location permission for legacy BLE scanning. The project does not request location permission on Android 12+.

## Technical details and development

For protocol behavior, reconnect strategy, architecture, telemetry registers, database migration and build instructions, read the [technical reference](docs/TECHNICAL.md). See the [installation help](docs/INSTALL.md), [compatibility & testing guide](docs/COMPATIBILITY.md), [ride statistics details](docs/STATISTICS.md), [contributing guide](CONTRIBUTING.md) and [security policy](SECURITY.md).

**Build:** JDK 17 · Android SDK 35 · Gradle 8.9.

```bash
gradle :app:assembleDebug
```

## Built on G30, open to the Ninebot / Segway community

I built this app for my own Ninebot Max G30 and decided to share it with other riders. It's an independent hobby project, not a universal scooter controller or a commercial anti-theft product.

**Own any Ninebot / Segway model?** [Submit a compatibility test report](https://github.com/Bl0ck154/Ninebot-scooter-blocker/issues/new?template=compatibility_report.yml), even if the app only discovers your scooter or cannot connect. If you've found a bug when **switching scooters or reconnecting**, please [open an issue](https://github.com/Bl0ck154/Ninebot-scooter-blocker/issues) or [send a pull request](https://github.com/Bl0ck154/Ninebot-scooter-blocker/pulls). Real-device results, compatibility notes and focused fixes help far more than guesses.

Please include the app version, scooter model and firmware where useful, but **remove serial numbers, BLE addresses and other private identifiers** from logs or screenshots.

Distributed under the [MIT License](LICENSE). Segway and Ninebot trademarks belong to their respective owners.

---

<div align="center"><strong>Connect · monitor · lock · ride.</strong></div>
