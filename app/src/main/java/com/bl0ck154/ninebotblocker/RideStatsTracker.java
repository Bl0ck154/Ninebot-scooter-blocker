package com.bl0ck154.ninebotblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.util.Calendar;
import java.util.List;

/** Turns live telemetry into persistent movement-based ride/day/week/month statistics. */
public final class RideStatsTracker {
    public static final String PREF_RIDE_PAUSE_MINUTES = "ride_pause_minutes";
    public static final int DEFAULT_PAUSE_MINUTES = 20;
    private static final long CONTINUE_WINDOW_MS = 24L * 60L * 60L * 1000L;
    private static final long FRESH_BASELINE_MS = 3000L;
    private static final long STATS_WRITE_INTERVAL_MS = 5000L;

    public static final int PERIOD_DAY = 0;
    public static final int PERIOD_WEEK = 1;
    public static final int PERIOD_MONTH = 2;

    public static final class Snapshot {
        public final RideStatsStore.RideRecord currentRide;
        public final RideStatsStore.RideRecord previousRide;
        public final boolean canContinuePrevious;
        public final int pauseMinutes;
        private final double liveDistanceKm;

        Snapshot(RideStatsStore.RideRecord currentRide, RideStatsStore.RideRecord previousRide,
                 boolean canContinuePrevious, int pauseMinutes, double liveDistanceKm) {
            this.currentRide = currentRide;
            this.previousRide = previousRide;
            this.canContinuePrevious = canContinuePrevious;
            this.pauseMinutes = pauseMinutes;
            this.liveDistanceKm = liveDistanceKm;
        }

        public double currentDistanceKm() { return liveDistanceKm; }
    }

    private final RideStatsStore store;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String scooterKey;
    private boolean connected;
    private long baselineUntil;
    private long currentRideId = -1L;
    private long lastWriteAt;
    private long lastMovementAt;
    private long pendingMovementAt;
    private Double observedOdometer;
    private Integer observedBattery;
    private double pendingDistance;
    private double pendingDischarged;
    private double pendingCharged;
    private double pendingMaxSpeed;

    private final Runnable closeAfterPause = new Runnable() {
        @Override public void run() {
            if (currentRideId < 0L || scooterKey == null || lastMovementAt <= 0L) return;
            long now = System.currentTimeMillis();
            long expiry = lastMovementAt + pauseTimeoutMs();
            if (now < expiry) {
                main.postDelayed(this, expiry - now);
                return;
            }
            closeCurrentForInactivity(now);
        }
    };

    public RideStatsTracker(Context context) {
        Context app = context.getApplicationContext();
        store = RideStatsStore.get(app);
        prefs = app.getSharedPreferences(ScooterRepository.PREFS, Context.MODE_PRIVATE);
    }

    public void onConnected(String key) {
        if (key == null) return;
        boolean wasConnected = connected;
        scooterKey = key;
        connected = true;
        main.removeCallbacks(closeAfterPause);
        if (wasConnected) return;

        long now = System.currentTimeMillis();
        RideStatsStore.RideRecord open = store.openRide(key);
        if (open != null && RideSessionPolicy.isExpired(open.lastSeenAt, now, pauseTimeoutMs())) {
            store.closeRide(open.id, open.lastSeenAt);
            open = null;
            baselineUntil = now + FRESH_BASELINE_MS;
        }
        adoptRide(open);
        lastWriteAt = now;
        if (open != null) scheduleClose();
    }

    public void onDisconnected() {
        if (connected && currentRideId >= 0L) flushPending(System.currentTimeMillis());
        connected = false;
        scheduleClose();
    }

    public void onTelemetry(String key, ScooterTelemetry telemetry) {
        if (!connected || key == null || telemetry == null) return;
        scooterKey = key;
        long now = System.currentTimeMillis();

        // A ride expires from the last real movement, even if BLE stayed connected and kept
        // delivering perfectly fresh stationary telemetry for hours.
        if (currentRideId >= 0L
                && RideSessionPolicy.isExpired(lastMovementAt, now, pauseTimeoutMs())) {
            closeCurrentForInactivity(now);
        }

        Double previousOdometer = observedOdometer;
        Integer previousBattery = observedBattery;
        Double odometer = telemetry.getTotalDistance();
        Integer battery = telemetry.getBatteryPercent();
        Double speed = telemetry.getSpeed();

        double distanceDelta = 0.0;
        if (odometer != null && previousOdometer != null && now >= baselineUntil) {
            double delta = odometer - previousOdometer;
            if (delta >= 0.0 && delta <= 20.0) distanceDelta = delta;
        }

        boolean moving = RideSessionPolicy.isMoving(distanceDelta, speed);

        if (currentRideId < 0L) {
            // Keep a baseline while parked but do not create an empty ride just because Bluetooth
            // connected. The session begins only when the scooter actually starts moving.
            observedOdometer = odometer;
            observedBattery = battery;
            if (!moving) return;

            Double startOdometer = previousOdometer != null ? previousOdometer : odometer;
            long id = store.startRide(key, now, battery, startOdometer);
            RideStatsStore.RideRecord started = store.rideById(id);
            adoptRide(started);
            lastWriteAt = now;
            lastMovementAt = now;
            pendingMovementAt = now;
            observedOdometer = odometer;
            observedBattery = battery;
            pendingDistance = Math.max(0.0, distanceDelta);
            if (speed != null) pendingMaxSpeed = Math.max(0.0, speed);
            scheduleClose();
            return;
        }

        if (distanceDelta > 0.0) pendingDistance += distanceDelta;

        if (battery != null && previousBattery != null) {
            int delta = battery - previousBattery;
            if (delta < 0) pendingDischarged += -delta;
            else if (delta > 0) pendingCharged += delta;
        }
        observedBattery = battery;
        observedOdometer = odometer;

        if (speed != null) pendingMaxSpeed = Math.max(pendingMaxSpeed, Math.max(0.0, speed));
        if (moving) {
            lastMovementAt = now;
            pendingMovementAt = now;
            scheduleClose();
        }

        // Keep live values in RAM and persist them in one small transaction every five seconds.
        // Disconnect/end/export paths force a final flush, so no ride data is intentionally lost.
        if (now - lastWriteAt >= STATS_WRITE_INTERVAL_MS) flushPending(now);
    }

    private void flushPending(long now) {
        if (currentRideId < 0L || scooterKey == null) return;
        long connectedDelta = lastWriteAt <= 0L ? 0L : Math.max(0L, Math.min(10000L, now - lastWriteAt));
        Long movementAt = pendingMovementAt > 0L ? pendingMovementAt : null;
        store.applySample(currentRideId, scooterKey, now, movementAt, observedBattery, observedOdometer,
                pendingDistance, pendingDischarged, pendingCharged, pendingMaxSpeed, connectedDelta);
        pendingDistance = 0.0;
        pendingDischarged = 0.0;
        pendingCharged = 0.0;
        pendingMaxSpeed = 0.0;
        pendingMovementAt = 0L;
        lastWriteAt = now;
    }

    private void adoptRide(RideStatsStore.RideRecord ride) {
        currentRideId = ride == null ? -1L : ride.id;
        observedOdometer = ride == null ? observedOdometer : ride.lastOdometer;
        observedBattery = ride == null ? observedBattery : ride.lastBattery;
        lastMovementAt = ride == null ? 0L : ride.lastSeenAt;
        pendingMovementAt = 0L;
        pendingDistance = 0.0;
        pendingDischarged = 0.0;
        pendingCharged = 0.0;
        pendingMaxSpeed = 0.0;
    }

    private void clearCurrentRideKeepBaselines(long now) {
        currentRideId = -1L;
        lastMovementAt = 0L;
        pendingMovementAt = 0L;
        pendingDistance = 0.0;
        pendingDischarged = 0.0;
        pendingCharged = 0.0;
        pendingMaxSpeed = 0.0;
        lastWriteAt = now;
        main.removeCallbacks(closeAfterPause);
    }

    private void closeCurrentForInactivity(long now) {
        if (currentRideId < 0L) return;
        if (connected) flushPending(now);
        long endedAt = lastMovementAt > 0L ? lastMovementAt : now;
        store.closeRide(currentRideId, endedAt);
        clearCurrentRideKeepBaselines(now);
    }

    private void scheduleClose() {
        main.removeCallbacks(closeAfterPause);
        if (currentRideId < 0L || lastMovementAt <= 0L) return;
        long remaining = Math.max(0L,
                lastMovementAt + pauseTimeoutMs() - System.currentTimeMillis());
        main.postDelayed(closeAfterPause, remaining);
    }

    public Snapshot snapshot(String key) {
        if (key == null) return new Snapshot(null, null, false, getPauseMinutes(), 0.0);
        RideStatsStore.RideRecord open = store.openRide(key);
        long now = System.currentTimeMillis();
        long movementAt = open == null ? 0L : open.lastSeenAt;
        if (open != null && key.equals(scooterKey) && open.id == currentRideId && lastMovementAt > 0L) {
            movementAt = lastMovementAt;
        }
        if (open != null && RideSessionPolicy.isExpired(movementAt, now, pauseTimeoutMs())) {
            if (open.id == currentRideId) closeCurrentForInactivity(now);
            else store.closeRide(open.id, movementAt);
            open = null;
        }

        RideStatsStore.RideRecord previous = store.lastClosedRide(key);
        boolean canContinue = previous != null && open == null
                && now - previous.lastSeenAt <= CONTINUE_WINDOW_MS;
        double liveDistance = open == null ? 0.0 : open.distanceKm;
        if (open != null && connected && key.equals(scooterKey) && open.id == currentRideId) {
            liveDistance += pendingDistance;
        }
        return new Snapshot(open, previous, canContinue, getPauseMinutes(), liveDistance);
    }

    public boolean endRideNow(String key) {
        if (key == null) return false;
        RideStatsStore.RideRecord open = store.openRide(key);
        if (open == null) return false;
        long now = System.currentTimeMillis();
        if (connected && open.id == currentRideId) flushPending(now);
        store.closeRide(open.id, now);
        if (open.id == currentRideId) clearCurrentRideKeepBaselines(now);
        return true;
    }

    public boolean continuePreviousRide(String key) {
        if (key == null) return false;
        if (connected && currentRideId >= 0L) flushPending(System.currentTimeMillis());
        RideStatsStore.RideRecord open = store.openRide(key);
        if (open != null && open.counted) return false;
        RideStatsStore.RideRecord previous = store.lastClosedRide(key);
        long now = System.currentTimeMillis();
        if (previous == null || now - previous.lastSeenAt > CONTINUE_WINDOW_MS) return false;
        if (open != null) store.deleteRide(open.id);
        store.reopenRide(previous.id, now);
        scooterKey = key;
        RideStatsStore.RideRecord reopened = store.openRide(key);
        adoptRide(reopened);
        lastWriteAt = now;
        scheduleClose();
        return true;
    }

    public RideStatsStore.PeriodSummary period(String key, int period) {
        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        zeroTime(from);
        zeroTime(to);
        if (period == PERIOD_WEEK) {
            int day = from.get(Calendar.DAY_OF_WEEK);
            int offset = (day + 5) % 7;
            from.add(Calendar.DAY_OF_MONTH, -offset);
            to.setTimeInMillis(from.getTimeInMillis());
            to.add(Calendar.DAY_OF_MONTH, 6);
        } else if (period == PERIOD_MONTH) {
            from.set(Calendar.DAY_OF_MONTH, 1);
            to.setTimeInMillis(from.getTimeInMillis());
            to.add(Calendar.MONTH, 1);
            to.add(Calendar.DAY_OF_MONTH, -1);
        }
        return store.period(key, RideStatsStore.dayKey(from.getTimeInMillis()),
                RideStatsStore.dayKey(to.getTimeInMillis()));
    }

    public List<RideStatsStore.RideRecord> recentRides(String key, int limit) {
        return store.recentRides(key, limit);
    }

    public int getPauseMinutes() {
        int value = prefs.getInt(PREF_RIDE_PAUSE_MINUTES, DEFAULT_PAUSE_MINUTES);
        return value == 30 || value == 60 ? value : DEFAULT_PAUSE_MINUTES;
    }

    public void setPauseMinutes(int minutes) {
        int safe = minutes == 30 || minutes == 60 ? minutes : DEFAULT_PAUSE_MINUTES;
        prefs.edit().putInt(PREF_RIDE_PAUSE_MINUTES, safe).apply();
        scheduleClose();
    }

    public long pauseTimeoutMs() { return getPauseMinutes() * 60L * 1000L; }

    public String exportJson() throws JSONException {
        if (connected && currentRideId >= 0L) flushPending(System.currentTimeMillis());
        return store.exportJson();
    }

    public void importJsonReplace(String json) throws JSONException {
        store.importJsonReplace(json);
        currentRideId = -1L;
        lastMovementAt = 0L;
        pendingMovementAt = 0L;
        observedOdometer = null;
        observedBattery = null;
        pendingDistance = pendingDischarged = pendingCharged = pendingMaxSpeed = 0.0;
        baselineUntil = System.currentTimeMillis() + FRESH_BASELINE_MS;
        if (connected && scooterKey != null) {
            RideStatsStore.RideRecord open = store.openRide(scooterKey);
            if (open != null && !RideSessionPolicy.isExpired(
                    open.lastSeenAt, System.currentTimeMillis(), pauseTimeoutMs())) {
                adoptRide(open);
                scheduleClose();
            }
        }
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }
}
