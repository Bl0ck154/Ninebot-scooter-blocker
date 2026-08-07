# Ninebot Scooter Blocker

Ninebot Scooter Blocker is a lightweight Android companion app for **Ninebot Max G30**.

The project intentionally keeps the G30 BLE/authentication path that already works in this repository and builds a small daily-use companion around it. It is not a firmware flashing tool.

## Features

- automatic BLE connection to the remembered scooter;
- SHU-compatible G30 authentication using the existing `NinebotCrypto` transport;
- lock / unlock from the app, notification, or home shortcut;
- live battery percentage, voltage, current and calculated power;
- live speed;
- trip distance, odometer and remaining range;
- controller and battery temperature when returned by the scooter;
- foreground service for an ongoing connection while the Activity is closed;
- persistent, throttled battery/ride notification with a dynamic lock/unlock action;
- reconnect backoff: 1 s, 2 s, 5 s, 10 s, then 30 s;
- saved BLE address/name plus authenticated scooter serial verification;
- adaptive telemetry polling and no permanent CPU wake lock;
- compact daily-use dashboard and adaptive launcher icon.

## Tested model

- **Ninebot Max G30** — the existing BLE authentication and lock/unlock path in this repository is the hardware-tested compatibility baseline.

Do not assume support for G30LP, G30D, G30P, G2, F-series or other Segway/Ninebot models merely because some protocol registers are related. They are not listed as tested here.

The telemetry implementation targets the documented Ninebot KickScooter Max/G30 register map. Remaining range is interpreted in 10 m units, matching the Segway BLE SDK and the observed G30 value. Unsupported/unreturned values stay blank rather than being fabricated.

## Architecture

The Android side is deliberately small and Java-first to avoid rewriting the working transport only for a language migration:

`ScooterBleManager -> NinebotBleClient -> G30Protocol -> ScooterRepository -> UI / ScooterService`

- `NinebotBleClient` keeps the known-good Nordic UART UUIDs, SHU 2.7 crypto/authentication sequence, 20-byte BLE fragmentation and serialized write queue.
- `ScooterBleManager` owns scanning and connection coordination.
- `G30Protocol` owns register addresses, read packets and telemetry parsing.
- `ScooterRepository` is the single source of truth for connection state, remembered identity, lock actions, reconnect and telemetry.
- `ScooterService` owns the foreground notification/background lifetime.
- `MainActivity` renders state and never handles `BluetoothGatt` directly.

Connection states are `DISCONNECTED`, `SCANNING`, `CONNECTING`, `CONNECTED`, `READY`, `RECONNECTING`, and `ERROR`.

## Telemetry cadence

While moving, one read-only protocol request is scheduled roughly every 500 ms, rotating the fast metrics so battery/speed/current/voltage update without parallel GATT writes. When stationary the cadence relaxes to roughly 1 s per request. Slower range/trip/odometer/temperature reads are interleaved. Notification rendering is throttled to about 1.5 s.

## Android permissions

- Android 12+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`.
- Android 13+: `POST_NOTIFICATIONS` when the persistent notification is enabled.
- Android 11 and older: fine location only because those Android versions gate BLE scanning behind it.
- Foreground connection uses the `connectedDevice` service type.

The app does not request location on Android 12+ and does not hold a permanent wake lock. If a vendor battery optimizer kills the service, the dashboard links to Android's battery optimization settings instead of silently requesting broad extra permissions.

## Safety / scope

This app does **not** include firmware flashing, region changes, serial changes, motor tuning or speed-limit modification. Telemetry requests are read-only. Existing G30 lock/unlock packets remain delegated to the already working SHU-compatible implementation.

## Build / CI

Every push to `main` runs:

1. Android lint;
2. unit tests for protocol/crypto parsing;
3. stable-signed debug APK build and signature verification;
4. signed release APK build only when release-signing secrets are configured;
5. workflow artifact upload;
6. GitHub Release publication for the app version.

Current companion release: **v0.8.1**.
