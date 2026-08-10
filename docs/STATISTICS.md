# Ride statistics

Ninebot Scooter Blocker keeps a local ride history for the remembered scooter and turns the G30 odometer stream into useful courier-style sessions, daily totals and longer-term activity.

## Ride sessions

A **ride** is based on real movement, not on Bluetooth uptime.

The app watches odometer deltas and speed. A session starts when movement is observed and remains active through short stops. If no real movement is seen for the configured inactivity window, that ride is closed even when the phone is still connected to the scooter over BLE.

Available inactivity windows:

- 20 minutes
- 30 minutes
- 60 minutes

This means a short shop or restaurant stop can stay inside the same ride, while going home for several hours and riding again becomes a new session automatically.

### Manual controls

**End ride** closes the active ride immediately.

**Continue previous** can reopen the most recent ride when two sessions should intentionally be joined and the new session has not accumulated movement yet.

## Distance calculation

Session distance is calculated from **total odometer deltas** instead of the scooter's temporary trip register. This keeps the session stable across scooter sleep/reconnect cycles.

The first telemetry samples after a fresh connection are treated as a baseline so reconnecting cannot accidentally create a large distance jump.

## Activity calendar

The Statistics screen includes a tappable **17-week activity heatmap**, inspired by GitHub's contribution calendar.

Each square represents one local calendar day:

- empty / very light — no or almost no riding;
- medium intensity — a moderate amount of distance;
- darkest intensity — one of the heavier riding days in the visible range.

The heatmap scales relative to the recent riding history, so it stays useful whether the scooter is used casually or for long courier shifts.

Tap any square to select that day and inspect its totals and rides.

## Day / Week / Month

The segmented control switches between:

- **Day** — one selected calendar day;
- **Week** — Monday through Sunday containing the selected date;
- **Month** — the full calendar month containing the selected date.

Use the left/right date arrows to move through periods. The Statistics screen updates the visible distance, ride count, connected time, maximum speed, battery used/charged and the ride list for that period.

## Stored values

For each ride the database keeps:

- start/end time;
- last real movement time;
- distance;
- maximum observed speed;
- connected time;
- start/latest battery percentage;
- observed battery percentage used;
- observed battery percentage charged;
- odometer baseline/latest value;
- scooter identity.

Daily aggregates are stored separately for efficient calendar and Day/Week/Month queries.

## Scooter identity

Statistics are separated by scooter.

The preferred identity is the remembered scooter serial number. If a serial is unavailable, the BLE MAC address is used as a fallback key.

## Persistence and writes

Live ride values are accumulated in memory and persisted in small batches rather than writing SQLite for every telemetry packet. Disconnect, manual ride end and export paths force a final flush.

The database is local to the phone and does not require an account or cloud service.

## Import / export

**Export JSON** creates a portable backup containing rides and daily aggregates.

**Import JSON** replaces the local statistics database with the selected backup. Scooter BLE settings and pairing information are not replaced.

## v0.12 migration

Older statistics builds refreshed the ride timestamp from general telemetry. A scooter could therefore remain in one ride for hours merely because BLE stayed connected while parked.

Database schema v2 separates **last movement** from general telemetry activity. Legacy open v1 rides are closed during migration so an old telemetry-based timestamp cannot silently merge into a new ride.
