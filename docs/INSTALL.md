# Install and connect Ninebot Scooter Blocker

Ninebot Scooter Blocker is a free, independent Android app built and tested with **one Ninebot Max G30**. It is distributed as an APK from the project's own GitHub Releases page; it is **not on Google Play**.

## Before you start

- An Android phone running **Android 8.0 or newer** with Bluetooth Low Energy.
- A **Ninebot Max G30** you own or are authorized to operate. Other scooters are not verified.
- Your scooter switched on and within Bluetooth range.

## Install the APK

1. Go to the [latest official release](https://github.com/Bl0ck154/Ninebot-scooter-blocker/releases/latest).
2. Download the file named `Ninebot-scooter-blocker-v<version>.apk` under **Assets**.
3. Open the downloaded APK. Android may ask you to allow installation from **this source** (for example, your browser or Files app). Approve it only if you trust the GitHub repository and APK.
4. Follow Android's installation prompt, then open **Ninebot Scooter Blocker**.
5. Allow Bluetooth permissions when prompted, then choose your own scooter.

You do **not** need to flash firmware, change the scooter's serial number, sign up for an account or pay for a subscription.

**Safety:** Use the software lock only on a scooter you own or are authorized to control. For unattended parking, use a suitable physical lock too.

## Get the convenient notification

In the app, enable **Auto connect** and **Persistent notification**. With a connection, the notification can show battery %, current session distance, speed, connection/signal information and charging status, and offers **LOCK / UNLOCK** without opening the app.

On **Android 13+**, allow notification permission. If background reconnection is unreliable, open the app's **Battery optimization help** and review your phone's background app settings; Android manufacturers handle these differently.

When Bluetooth disconnects, the notification can retain the **last known** battery % and session distance for **up to 30 minutes**, and the action becomes **RECONNECT**. These numbers are **not live while disconnected**. The notification may be dismissed while offline and it expires after the grace period.

The optional **Full-charge sound alert** works only while charging can actually be monitored through a connected phone.

## If it doesn't connect

- Check Bluetooth is on, the scooter is awake and you're close enough.
- Make sure another app on the phone is not already connected to the scooter's control channel. Temporarily close other scooter apps and retry.
- Check the Bluetooth permission and, if using the live notification, notification permission.
- Try **RECONNECT** or open the app and select the saved scooter again.
- Review Android battery/background restrictions when reconnection works in the foreground but not reliably in the background.
- Remember: being visible in Bluetooth scanning does not mean another Ninebot model is compatible.

**Changing between different scooters is not thoroughly tested.** The original app was developed using one privately owned G30. Please share reproducible, sanitized reports if you discover a problem.

## Privacy and offline use

Ride history stays in local SQLite storage on your phone and can be exported or imported as JSON. The app does not create GPS ride routes or require a cloud account. On Android 11 and older, BLE scanning requires Android's legacy location permission; on Android 12+ the app does not request it.

## Report a problem or help test other models

[Open an issue](https://github.com/Bl0ck154/Ninebot-scooter-blocker/issues/new/choose) with the Android version, app version, scooter model/firmware and steps to reproduce. **Remove serial numbers, BLE MAC addresses, location information and other personal data from logs and screenshots.**

See the [compatibility testing guide](COMPATIBILITY.md) if you'd like to help add support for another model.
