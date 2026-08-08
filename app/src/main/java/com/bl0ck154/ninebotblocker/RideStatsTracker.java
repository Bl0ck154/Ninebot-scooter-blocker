package com.bl0ck154.ninebotblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.util.Calendar;
import java.util.List;

/** Turns live telemetry into persistent ride/day/week/month statistics. */
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
    private boolean suppressUntilDisconnected;
    private long baselineUntil;
    private long currentRideId = -1L;
    private long lastWriteAt;
    private Double observedOdometer;
    private Integer observedBattery;
    private double pendingDistance;
    private double pendingDischarged;
    private double pendingCharged;
    private double pendingMaxSpeed;

    private final Runnable closeAfterPause = new Runnable() {
        @Override public void run() {
            if (connected || scooterKey == null) return;
            RideStatsStore.RideRecord open = store.openRide(scooterKey);
            if (open == null) return;
            long now = System.currentTimeMillis();
            long expiry = open.lastSeenAt + pauseTimeoutMs();
            if (now < expiry) {
                main.postDelayed(this, expiry - now);
                return;
            }
            store.closeRide(open.id, open.lastSeenAt);
            currentRideId = -1L;
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
        if (!wasConnected) {
            suppressUntilDisconnected = false;
            RideStatsStore.RideRecord ride = ensureRide(System.currentTimeMillis(), null, null);
            adoptRide(ride);
            lastWriteAt = System.currentTimeMillis();
        }
    }

    public void onDisconnected() {
        if (connected) flushPending(System.currentTimeMillis());
        connected = false;
        suppressUntilDisconnected = false;
        scheduleClose();
    }

    public void onTelemetry(String key, ScooterTelemetry telemetry) {
        if (!connected || key == null || telemetry == null || suppressUntilDisconnected) return;
        scooterKey = key;
        long now = System.currentTimeMillis();
        RideStatsStore.RideRecord ride = ensureRide(now, telemetry.getBatteryPercent(), telemetry.getTotalDistance());
        if (ride == null) return;
        if (ride.id != currentRideId) adoptRide(ride);

        Double odometer = telemetry.getTotalDistance();
        if (odometer != null) {
            if (observedOdometer != null && now >= baselineUntil) {
                double delta = odometer - observedOdometer;
                if (delta >= 0.0 && delta <= 20.0) pendingDistance += delta;
            }
            observedOdometer = odometer;
        }

        Integer battery = telemetry.getBatteryPercent();
        if (battery != null) {
            if (observedBattery != null) {
                int delta = battery - observedBattery;
                if (delta < 0) pendingDischarged += -delta;
                else if (delta > 0) pendingCharged += delta;
            }
            observedBattery = battery;
        }

        Double speed = telemetry.getSpeed();
        if (speed != null) pendingMaxSpeed = Math.max(pendingMaxSpeed, Math.max(0.0, speed));

        // Keep live values in RAM and persist them in one small transaction every five seconds.
        // Disconnect/end/export paths force a final flush, so no ride data is intentionally lost.
        if (now - lastWriteAt >= STATS_WRITE_INTERVAL_MS) flushPending(now);
    }

    private void flushPending(long now) {
        if (currentRideId < 0L || scooterKey == null) return;
        long connectedDelta = lastWriteAt <= 0L ? 0L : Math.max(0L, Math.min(10000L, now - lastWriteAt));
        store.applySample(currentRideId, scooterKey, now, observedBattery, observedOdometer,
                pendingDistance, pendingDischarged, pendingCharged, pendingMaxSpeed, connectedDelta);
        pendingDistance = 0.0;
        pendingDischarged = 0.0;
        pendingCharged = 0.0;
        pendingMaxSpeed = 0.0;
        lastWriteAt = now;
    }

    private void adoptRide(RideStatsStore.RideRecord ride) {
        currentRideId = ride == null ? -1L : ride.id;
        observedOdometer = ride == null ? null : ride.lastOdometer;
        observedBattery = ride == null ? null : ride.lastBattery;
        pendingDistance = 0.0;
        pendingDischarged = 0.0;
        pendingCharged = 0.0;
        pendingMaxSpeed = 0.0;
    }

    private RideStatsStore.RideRecord ensureRide(long now, Integer battery, Double odometer) {
        if (scooterKey == null || suppressUntilDisconnected) return null;
        RideStatsStore.RideRecord open = store.openRide(scooterKey);
        if (open != null && now - open.lastSeenAt > pauseTimeoutMs()) {
            store.closeRide(open.id, open.lastSeenAt);
            open = null;
            baselineUntil = now + FRESH_BASELINE_MS;
        }
        if (open == null) {
            long id = store.startRide(scooterKey, now, battery, odometer);
            open = store.rideById(id);
        }
        return open;
    }

    private void scheduleClose() {
        main.removeCallbacks(closeAfterPause);
        if (scooterKey == null) return;
        RideStatsStore.RideRecord open = store.openRide(scooterKey);
        if (open == null) return;
        long delay = Math.max(0L, open.lastSeenAt + pauseTimeoutMs() - System.currentTimeMillis());
        main.postDelayed(closeAfterPause, delay);
    }

    public Snapshot snapshot(String key) {
        if (key == null) return new Snapshot(null, null, false, getPauseMinutes(), 0.0);
        RideStatsStore.RideRecord open = store.openRide(key);
        long now = System.currentTimeMillis();
        if (open != null && !connected && now - open.lastSeenAt > pauseTimeoutMs()) {
            store.closeRide(open.id, open.lastSeenAt);
            open = null;
            currentRideId = -1L;
        }
        RideStatsStore.RideRecord previous = store.lastClosedRide(key);
        boolean canContinue = previous != null && now - previous.lastSeenAt <= CONTINUE_WINDOW_MS
                && (open == null || !open.counted);
        double liveDistance = open == null ? 0.0 : open.distanceKm;
        if (open != null && connected && key.equals(scooterKey) && open.id == currentRideId) {
            liveDistance += pendingDistance;
        }
        return new Snapshot(open, previous, canContinue, getPauseMinutes(), liveDistance);
    }

    public boolean endRideNow(String key) {
        if (connected) flushPending(System.currentTimeMillis());
        RideStatsStore.RideRecord open = store.openRide(key);
        if (open == null) return false;
        store.closeRide(open.id, System.currentTimeMillis());
        currentRideId = -1L;
        suppressUntilDisconnected = connected;
        main.removeCallbacks(closeAfterPause);
        return true;
    }

    public boolean continuePreviousRide(String key) {
        if (key == null) return false;
        if (connected) flushPending(System.currentTimeMillis());
        RideStatsStore.RideRecord open = store.openRide(key);
        if (open != null && open.counted) return false;
        RideStatsStore.RideRecord previous = store.lastClosedRide(key);
        if (previous == null || System.currentTimeMillis() - previous.lastSeenAt > CONTINUE_WINDOW_MS) return false;
        if (open != null) store.deleteRide(open.id);
        store.reopenRide(previous.id, System.currentTimeMillis());
        scooterKey = key;
        suppressUntilDisconnected = false;
        RideStatsStore.RideRecord reopened = store.openRide(key);
        adoptRide(reopened);
        lastWriteAt = System.currentTimeMillis();
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
        if (!connected) scheduleClose();
    }

    public long pauseTimeoutMs() { return getPauseMinutes() * 60L * 1000L; }

    public String exportJson() throws JSONException {
        if (connected) flushPending(System.currentTimeMillis());
        return store.exportJson();
    }

    public void importJsonReplace(String json) throws JSONException {
        store.importJsonReplace(json);
        currentRideId = -1L;
        observedOdometer = null;
        observedBattery = null;
        pendingDistance = pendingDischarged = pendingCharged = pendingMaxSpeed = 0.0;
        baselineUntil = System.currentTimeMillis() + FRESH_BASELINE_MS;
        if (connected && scooterKey != null) adoptRide(store.openRide(scooterKey));
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }
}
