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

    public static final int PERIOD_DAY = 0;
    public static final int PERIOD_WEEK = 1;
    public static final int PERIOD_MONTH = 2;

    public static final class Snapshot {
        public final RideStatsStore.RideRecord currentRide;
        public final RideStatsStore.RideRecord previousRide;
        public final boolean canContinuePrevious;
        public final int pauseMinutes;

        Snapshot(RideStatsStore.RideRecord currentRide, RideStatsStore.RideRecord previousRide,
                 boolean canContinuePrevious, int pauseMinutes) {
            this.currentRide = currentRide;
            this.previousRide = previousRide;
            this.canContinuePrevious = canContinuePrevious;
            this.pauseMinutes = pauseMinutes;
        }

        public double currentDistanceKm() {
            return currentRide == null ? 0.0 : currentRide.distanceKm;
        }
    }

    private final RideStatsStore store;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String scooterKey;
    private boolean connected;
    private boolean suppressUntilDisconnected;
    private long lastSampleAt;
    private long baselineUntil;

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
            lastSampleAt = 0L;
            ensureRide(System.currentTimeMillis(), null, null);
        }
    }

    public void onDisconnected() {
        connected = false;
        lastSampleAt = 0L;
        suppressUntilDisconnected = false;
        scheduleClose();
    }

    public void onTelemetry(String key, ScooterTelemetry telemetry) {
        if (!connected || key == null || telemetry == null || suppressUntilDisconnected) return;
        scooterKey = key;
        long now = System.currentTimeMillis();
        RideStatsStore.RideRecord ride = ensureRide(now, telemetry.getBatteryPercent(), telemetry.getTotalDistance());
        if (ride == null) return;

        Double currentOdometer = telemetry.getTotalDistance();
        Integer currentBattery = telemetry.getBatteryPercent();
        double distanceDelta = 0.0;
        double dischargedDelta = 0.0;
        double chargedDelta = 0.0;

        if (currentOdometer != null && ride.lastOdometer != null && now >= baselineUntil) {
            double delta = currentOdometer - ride.lastOdometer;
            // A G30 cannot legitimately gain tens of kilometres between telemetry samples.
            // The generous 20 km cap still allows a manually continued ride after a long pause.
            if (delta >= 0.0 && delta <= 20.0) distanceDelta = delta;
        }

        if (currentBattery != null && ride.lastBattery != null) {
            int delta = currentBattery - ride.lastBattery;
            if (delta < 0) dischargedDelta = -delta;
            else if (delta > 0) chargedDelta = delta;
        }

        long connectedDelta = 0L;
        if (lastSampleAt > 0L) {
            long delta = now - lastSampleAt;
            if (delta > 0L && delta <= 5000L) connectedDelta = delta;
        }
        lastSampleAt = now;

        Double speed = telemetry.getSpeed();
        store.applySample(ride.id, key, now, currentBattery, currentOdometer,
                distanceDelta, dischargedDelta, chargedDelta,
                speed == null ? 0.0 : speed, connectedDelta);
    }

    private RideStatsStore.RideRecord ensureRide(long now, Integer battery, Double odometer) {
        if (scooterKey == null || suppressUntilDisconnected) return null;
        RideStatsStore.RideRecord open = store.openRide(scooterKey);
        if (open != null && now - open.lastSeenAt > pauseTimeoutMs()) {
            store.closeRide(open.id, open.lastSeenAt);
            open = null;
            // The telemetry object can still contain pre-disconnect values. Give the immediate
            // odometer/battery reads time to establish a fresh baseline for the new ride.
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
        if (key == null) return new Snapshot(null, null, false, getPauseMinutes());
        RideStatsStore.RideRecord open = store.openRide(key);
        long now = System.currentTimeMillis();
        if (open != null && !connected && now - open.lastSeenAt > pauseTimeoutMs()) {
            store.closeRide(open.id, open.lastSeenAt);
            open = null;
        }
        RideStatsStore.RideRecord previous = store.lastClosedRide(key);
        boolean canContinue = previous != null && now - previous.lastSeenAt <= CONTINUE_WINDOW_MS
                && (open == null || !open.counted);
        return new Snapshot(open, previous, canContinue, getPauseMinutes());
    }

    public boolean endRideNow(String key) {
        RideStatsStore.RideRecord open = store.openRide(key);
        if (open == null) return false;
        store.closeRide(open.id, System.currentTimeMillis());
        suppressUntilDisconnected = connected;
        main.removeCallbacks(closeAfterPause);
        return true;
    }

    public boolean continuePreviousRide(String key) {
        if (key == null) return false;
        RideStatsStore.RideRecord open = store.openRide(key);
        if (open != null && open.counted) return false;
        RideStatsStore.RideRecord previous = store.lastClosedRide(key);
        if (previous == null || System.currentTimeMillis() - previous.lastSeenAt > CONTINUE_WINDOW_MS) return false;
        if (open != null) store.deleteRide(open.id);
        store.reopenRide(previous.id, System.currentTimeMillis());
        scooterKey = key;
        suppressUntilDisconnected = false;
        lastSampleAt = 0L;
        return true;
    }

    public RideStatsStore.PeriodSummary period(String key, int period) {
        Calendar from = Calendar.getInstance();
        Calendar to = Calendar.getInstance();
        zeroTime(from);
        zeroTime(to);
        if (period == PERIOD_WEEK) {
            int day = from.get(Calendar.DAY_OF_WEEK);
            int offset = (day + 5) % 7; // Monday = 0
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

    public String exportJson() throws JSONException { return store.exportJson(); }

    public void importJsonReplace(String json) throws JSONException {
        store.importJsonReplace(json);
        lastSampleAt = 0L;
        baselineUntil = System.currentTimeMillis() + FRESH_BASELINE_MS;
    }

    private static void zeroTime(Calendar c) {
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
    }
}