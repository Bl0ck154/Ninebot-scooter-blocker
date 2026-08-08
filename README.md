# Ninebot Scooter Blocker

Ninebot Scooter Blocker is a lightweight Android companion app built around the hardware-tested **Ninebot Max G30** BLE/authentication path in this repository. It is intentionally a daily-use companion, not a firmware flashing tool.

## Features

- automatic BLE connection to the remembered scooter;
- existing SHU-compatible authentication/crypto path preserved byte-for-byte for the G30 baseline;
- lock / unlock from the app, compact notification control, or home shortcut;
- actual lock-state read from the Ninebot read-only boolean status register (`0xB2`, lock bit `0x0002`);
- live battery percentage, voltage, current and calculated power;
- live speed, trip, odometer, remaining range and temperatures when returned by the scooter;
- reconnect backoff: 1 s, 2 s, 5 s, 10 s, then 30 s;
- persistent live notification that starts when the scooter is actually connected (`READY`);
- one always-visible compact lock/unlock control inside the collapsed notification;
- separate high-importance **Full charge alerts** notification channel with configurable Android sound;
- optional full-charge sound alert when a monitored battery rises to 100%;
- model-aware dashboard title when the model can be determined from serial or advertised name;
- Android 15/16 edge-to-edge/status-bar inset handling;
- adaptive telemetry polling and no permanent CPU wake lock;
- adaptive launcher icon.

## Compatibility

### Hardware-tested baseline

- **Ninebot Max G30** — connection, authentication and lock/unlock are the compatibility baseline for this project.

### Experimental legacy compatibility

Discovery accepts Ninebot/Segway BLE devices rather than hard-coding one model name. Several older Ninebot scooters share the Proto2 quick-register map (`0xB2`, `0xB4`, `0xB5`, `0xB7`, `0x70`, `0x71`, etc.). If such a scooter successfully completes the existing authentication flow, the app can use the common read-only telemetry and lock controls experimentally.

This does **not** mean SHU-level universal support. Newer Segway/Ninebot families may use Encryption2/Encryption3 authentication and different board routing; those are not silently treated as G30. Unknown devices get a generic `Ninebot / Segway Scooter` label instead of a fake model name. G2/F-series/etc. are not claimed as hardware-tested until actually verified.

## Lock state

After the connection becomes `READY`, the app immediately reads ESC quick status register `0xB2`. The Ninebot boolean state word uses bit `0x0002` for the software-lock state. The same read is periodically refreshed. The dashboard no longer duplicates this as a separate chip: the lock/unlock button itself is the visible lock indicator/action.

## Full-charge sound alert

Enable **Full-charge sound alert** in the dashboard. Arming it also enables **Persistent notification**, because Android needs the foreground connection while monitoring the scooter. If **Persistent notification** is switched off, the charge alert is switched off with it instead of blocking the toggle.

The sound is a separate Android notification channel named **Full charge alerts**. Tap **Full-charge sound settings** in the app to choose/disable the sound, vibration, or channel importance using Android's own settings. The alert fires once when the observed battery level rises to 100%; it does not fire just because the app starts while the scooter is already at 100%.

## Notification behavior

The live notification is silent and high-importance. It appears after the scooter reaches `READY`, shows `🟢 Connected` plus useful telemetry, and uses a single compact `🔐 LOCK` / `🔓 UNLOCK` control directly inside the collapsed notification. Duplicate lock text and the old expanded `LOCK` / `DISCONNECT` action row were removed.

When the live connection is lost, the foreground notification is removed. If the app process remains alive, the repository continues its normal reconnect backoff and starts the live notification again after the scooter becomes `READY`. Android does not permit a foreground service to run indefinitely with no notification, so a process that is fully killed by the OS while the scooter is offline cannot guarantee invisible background reconnect.

Turning off **Persistent notification** while the Activity is open does not call `disconnect()`. It only disables the live notification/background mode (and also disables the dependent full-charge alert); the open dashboard BLE session can remain connected.

## Architecture

`ScooterBleManager -> NinebotBleClient -> G30Protocol -> ScooterRepository -> UI / ScooterService`

- `NinebotBleClient` keeps the known-good Nordic UART UUIDs, crypto/authentication sequence, 20-byte BLE fragmentation and serialized write queue.
- `ScooterBleManager` owns scanning and connection coordination.
- `G30Protocol` owns the legacy Ninebot/G30 register map and telemetry parsing.
- `ScooterRepository` is the single source of truth for identity, model label, connection state, lock actions, reconnect and telemetry.
- `ScooterService` owns foreground lifetime, live notification and full-charge alert channel.
- `MainActivity` renders state and never handles `BluetoothGatt` directly.

Connection states are `DISCONNECTED`, `SCANNING`, `CONNECTING`, `CONNECTED`, `READY`, `RECONNECTING`, and `ERROR`.

## Android permissions

- Android 12+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- Android 13+: `POST_NOTIFICATIONS` for background/live and full-charge alerts.
- Android 11 and older: location only because those Android versions gate BLE scanning behind it.
- Foreground connection uses the `connectedDevice` service type.

The app does not request location on Android 12+ and does not hold a permanent wake lock.

## Safety / scope

This app does **not** include firmware flashing, region changes, serial changes, motor tuning or speed-limit modification. Telemetry requests are read-only. Existing G30 lock/unlock packets remain delegated to the already working implementation.

## Build / CI

Every push to `main` runs Android lint, unit tests, a stable-signed debug APK build/signature check, optional release signing when secrets are configured, artifact upload, and GitHub Release publication. CI reads the app version from `app/build.gradle.kts` instead of hard-coding the release tag in the workflow.

Current companion release: **v0.9.1**.
