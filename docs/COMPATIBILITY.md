# Compatibility and community testing

## What has really been tested?

| Scooter | Status | Evidence |
| --- | --- | --- |
| **Ninebot Max G30** | **Hardware tested** | Developer's **single personally owned scooter**, used to develop and test BLE authentication, status, live telemetry and software lock/unlock |
| G30 family variants (including G30LP) | **Not separately verified** | Similar names do not prove matching firmware, registers or authentication |
| Max G2, Max G3, F/E-series, other Segway / Ninebot models | **Not verified** | Different protocol/authentication versions may prevent the app from working |

The app supports selecting a different remembered scooter, but **switching between multiple scooters and reconnecting after a switch have not received comprehensive real-device testing**.

This is an independent hobby project. It is not an official Segway / Ninebot application and is not a universal scooter controller.

## How you can help

If you have another model and are willing to test, please [open a compatibility report](https://github.com/Bl0ck154/Ninebot-scooter-blocker/issues/new/choose).

A useful report includes:

- Exact model (e.g., G30 vs G30LP) and firmware version, if known
- Android phone model and Android version
- Application version from the release
- Whether the device is discovered, connects and authenticates
- Whether read-only values such as battery %, speed and odometer are correct
- Any disconnect/reconnect problems, particularly after changing the selected scooter
- What happened and the steps to reproduce it

**Only test lock/unlock on a scooter you own or are authorized to control, while stationary and in a safe place.** Never test control commands while riding or in traffic. Do not try firmware modifications as a prerequisite for this app.

Do not upload scooter serial numbers, MAC addresses, private ride histories, personal locations, or other identifying data.

## Pull requests

[Pull requests are welcome](https://github.com/Bl0ck154/Ninebot-scooter-blocker/pulls), especially for model-specific read-only telemetry, safely verified compatibility, reconnect fixes and clearer documentation. Please describe actual device evidence, not just protocol similarity. Follow [CONTRIBUTING.md](../CONTRIBUTING.md).

The aim is to expand compatibility **through owners testing their own scooters**, not to advertise unverified models as supported.
