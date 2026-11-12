# Ninebot / Segway compatibility — community testing welcome

Ninebot Scooter Blocker was built on **one personally owned Ninebot Max G30**. The app can discover devices advertising various Ninebot/Segway names, but that only helps *find* a scooter. It does **not** establish that the BLE transport, authentication, telemetry registers or lock/unlock commands are compatible.

**If you own a different Ninebot or Segway scooter, please help test!** Successful, partial and unsuccessful results all help identify what works and where changes are needed.

## Current evidence

| Model / model group | Status in this project | Next step |
| --- | --- | --- |
| **Ninebot Max G30** | ✅ **Hardware-tested on the developer's one G30:** connection, telemetry and lock/unlock | Additional phone/firmware reports are welcome |
| **G30 family variants** (e.g. G30D, G30E, G30LP) | 🧪 **Not verified** | Particularly interesting candidates for independent testing |
| **ES / E / F and other Ninebot / Segway models** | 🧪 **Not verified** | Test discovery, connection, and read-only values; some may use different protocols |
| **Max G2 / G3, F2 and newer series** | 🧪 **Not verified** | Different authentication/encryption and register layouts may require development |

This table is about **Ninebot Scooter Blocker**, not compatibility with the official Segway app, ScooterHacking Utility or another independent project. A model supported by those apps is **not automatically supported here**.

The app lets you change the selected scooter, but switching among multiple scooters and reconnecting after a switch have **not** been thoroughly hardware-tested.

## How to test safely

You don't need to try every feature to help. Start with the least invasive tests:

1. **Discovery:** Does your scooter appear in the picker? Record its *model name only*, not the BLE MAC address.
2. **Connection:** Does the app reach an authenticated/READY state? Or does it fail or reconnect repeatedly?
3. **Read-only telemetry:** Does the battery percentage look correct? What about speed while **not performing dangerous tests**, range and odometer? Report missing/obviously incorrect values.
4. **Background behavior:** If connected, does the notification display correctly? Do reconnect attempts work when you come back into range?
5. **Optional lock/unlock:** Only if you own or are authorized to operate the scooter and it is **stationary in a safe location**. Never attempt control tests in traffic or while riding. If you're unsure, **skip this step**.

A report like “F40 discovered, but authentication failed” is already valuable. Please **do not** try to extract passwords or bypass authentication for a public test report.

## Submit a compatibility report

**[🧪 Submit a structured compatibility report](https://github.com/Bl0ck154/Ninebot-scooter-blocker/issues/new?template=compatibility_report.yml)**

The form asks for your exact model, firmware if known, Android/app versions, and a result for each stage: Bluetooth discovery, authentication, read-only telemetry and optional software lock. **“Not tested” is an acceptable answer**. You may include brief reproduction steps.

Never share serial numbers, Bluetooth MAC addresses, authentication material, GPS/location data or unredacted logs/screenshots.

## Want to add support for your scooter?

[Pull requests](https://github.com/Bl0ck154/Ninebot-scooter-blocker/pulls) are welcome, especially improvements to discovery, safely verified protocol support, read-only telemetry, reconnect handling and documentation. Include the model and **real-device evidence**, not just assumptions based on names or model family. Follow [CONTRIBUTING.md](../CONTRIBUTING.md).

This is a hobby project — community contributions can gradually build a truthful compatibility list, one confirmed model and firmware at a time.
