# Ninebot Scooter Blocker

Minimal Android one-tap software lock app for Ninebot Max G30-family scooters.

The app binds to one scooter, connects directly over BLE UART and sends the Ninebot controller lock command (`NB_CTL_LOCK`, register `0x70`, value `1`).

## Current status

- Android app scaffold
- First-run scooter selection and persistent binding by BLE address
- One-tap LOCK action
- Legacy/plain Ninebot BLE UART protocol support
- GitHub Actions build producing an installable, debug-signed APK
- Explicit diagnostics for BLE/service/protocol failures

> Note: Some newer official dashboard/firmware combinations require the newer encrypted Mi/Ninebot authentication layer. The first version intentionally keeps the app small and implements the direct G30/Ninebot protocol used by compatible firmware. If the scooter exposes encrypted-only communication, the UI will report that the lock command could not be confirmed and encrypted auth can be added next.

## Protocol

Ninebot's published communication table defines:

- `0x70 NB_CTL_LOCK` — write signed 16-bit value `1` to lock; scooter resets automatically.
- `0x71 NB_CTL_UNLOCK` — write signed 16-bit value `1` to unlock.

This app only exposes **LOCK** to avoid accidental unlocks.

## Build

GitHub Actions builds on every push and uploads `ninebot-blocker-debug.apk`. Android debug APKs are signed automatically with a debug certificate and can be installed directly.

For a stable production signing key, configure a release keystore later; do not commit a private release key to the repository.
