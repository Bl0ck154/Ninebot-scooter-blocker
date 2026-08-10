# Contributing

Thank you for helping improve Ninebot Scooter Blocker.

Before opening a pull request:

1. Keep changes focused and explain the scooter model and firmware used for
   hardware testing.
2. Do not commit keystores, credentials, exported ride data, BLE addresses,
   scooter serial numbers, or other private device data.
3. Run `gradle :app:lintRelease :app:testDebugUnitTest :app:assembleDebug` with
   JDK 17 and Android SDK 35.
4. Describe behavioral changes and include logs with personal identifiers
   removed.

Protocol support must be backed by captured behavior or hardware testing.
Please avoid claims of compatibility for untested scooter families.
