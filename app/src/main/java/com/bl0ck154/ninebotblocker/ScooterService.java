package com.bl0ck154.ninebotblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.Locale;

/** Foreground owner for live scooter notification and full-charge monitoring. */
public final class ScooterService extends Service implements ScooterRepository.Listener {
    public static final String ACTION_START = "com.bl0ck154.ninebotblocker.START";
    public static final String ACTION_SYNC = "com.bl0ck154.ninebotblocker.SYNC";
    public static final String ACTION_MONITOR = "com.bl0ck154.ninebotblocker.MONITOR";
    public static final String ACTION_STOP_NOTIFICATION = "com.bl0ck154.ninebotblocker.STOP_NOTIFICATION";
    public static final String ACTION_DISMISS_OFFLINE = "com.bl0ck154.ninebotblocker.DISMISS_OFFLINE";
    public static final String ACTION_TOGGLE = "com.bl0ck154.ninebotblocker.TOGGLE";

    public static final String LIVE_CHANNEL_ID = "scooter_live_priority_v2";
    public static final String CHARGE_CHANNEL_ID = "scooter_charge_alerts_v2";

    private static final int NOTIFICATION_ID = 15430;
    private static final int CHARGE_NOTIFICATION_ID = 15431;
    private static final long NOTIFICATION_THROTTLE_MS = 1500L;
    private static final long OFFLINE_NOTIFICATION_GRACE_MS = 30L * 60L * 1000L;
    private static final long FULL_CHARGE_SESSION_GRACE_MS = 30_000L;
    private static final String SERVICE_PREFS = "scooter_service_state";
    private static final String PREF_OFFLINE_SINCE = "offline_since";
    private static final int SIGNAL_GREEN = 0xFF007E79;
    private static final int SIGNAL_RED = 0xFFB42318;
    private static final int SIGNAL_MUTED = 0xFF5F6368;
    private static volatile boolean running;
    // running=true only means Service.onCreate() has happened. Keep a second flag for the
    // notification's actual published state so a live service with a stale/offline RemoteViews
    // cannot block a READY resynchronization request.
    private static volatile boolean foregroundReady;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScooterRepository repository;
    private ScooterRepository.Snapshot pendingSnapshot;
    private long lastNotificationAt;
    private long disconnectedSince;
    private boolean transientAction;
    private Boolean transientTarget;
    private boolean monitoringMode;
    private boolean foregroundStarted;

    private Integer lastBatteryPercent;
    private boolean chargeSessionObserved;
    private boolean chargeProgressObserved;
    private boolean fullChargeAlertSent;
    private long lastConfirmedChargingAt;

    private final Runnable notificationRunnable = () -> {
        ScooterRepository.Snapshot snapshot = pendingSnapshot;
        pendingSnapshot = null;
        if (snapshot == null || !foregroundStarted) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(snapshot));
        foregroundReady = isReady(snapshot);
        lastNotificationAt = System.currentTimeMillis();
    };

    private final Runnable disconnectExpiryRunnable = new Runnable() {
        @Override public void run() {
            if (!foregroundStarted || repository == null) return;
            ScooterRepository.Snapshot current = repository.snapshot();
            if (isReady(current)) {
                clearOfflineSince();
                return;
            }
            long since = ensureOfflineSince();
            long elapsed = Math.max(0L, System.currentTimeMillis() - since);
            if (elapsed < OFFLINE_NOTIFICATION_GRACE_MS) {
                main.postDelayed(this, OFFLINE_NOTIFICATION_GRACE_MS - elapsed);
                return;
            }
            expireOfflineNotification();
        }
    };

    public static boolean isRunning() { return running; }

    public static void startForConnectedScooter(Context context) {
        // A service can still be alive while its foreground notification has been stopped or is
        // showing the last offline snapshot. In that case do not let the old `running` flag swallow
        // the READY event; send a one-shot sync command that republishes current repository state.
        if (running && foregroundReady) return;
        Intent intent = new Intent(context, ScooterService.class)
                .setAction(running ? ACTION_SYNC : ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException ignored) {}
    }

    public static void stopLiveNotification(Context context) {
        if (!running) return;
        try {
            context.startService(new Intent(context, ScooterService.class)
                    .setAction(ACTION_STOP_NOTIFICATION));
        } catch (RuntimeException ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        foregroundReady = false;
        disconnectedSince = offlineStatePrefs().getLong(PREF_OFFLINE_SINCE, 0L);
        ensureNotificationChannels(this);
        repository = ScooterRepository.get(this);
        repository.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP_NOTIFICATION.equals(action)) {
            repository.setFullChargeAlertEnabled(false);
            repository.setPersistentEnabled(false);
            monitoringMode = false;
            clearOfflineSince();
            pendingSnapshot = null;
            main.removeCallbacks(notificationRunnable);
            main.removeCallbacks(disconnectExpiryRunnable);
            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
            foregroundReady = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        ScooterRepository.Snapshot current = repository.snapshot();
        boolean currentReady = isReady(current);
        boolean backgroundWanted = repository.isPersistentEnabled() || repository.isFullChargeAlertEnabled();

        if (ACTION_DISMISS_OFFLINE.equals(action)) {
            if (!currentReady) expireOfflineNotification();
            return START_NOT_STICKY;
        }

        if (currentReady) clearOfflineSince();

        if (action == null && !backgroundWanted) {
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean commandAction = ACTION_TOGGLE.equals(action);
        boolean syncAction = ACTION_SYNC.equals(action);
        boolean monitorAction = ACTION_MONITOR.equals(action);
        if (!commandAction && !monitorAction && !syncAction && !currentReady) {
            if (ACTION_START.equals(action)) repository.setPersistentEnabled(true);
            repository.connectIfNeeded();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Never resurrect an offline foreground notification once its 30-minute grace has expired.
        // Reconnect attempts may continue best-effort in the repository while the process lives.
        if (!currentReady && (monitorAction || syncAction) && offlineGraceExpired()) {
            repository.connectIfNeeded();
            expireOfflineNotification();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(current));
        foregroundStarted = true;
        foregroundReady = currentReady;

        if (ACTION_TOGGLE.equals(action)) {
            monitoringMode = backgroundWanted;
            transientAction = !backgroundWanted;
            Boolean locked = repository.snapshot().telemetry.getLocked();
            transientTarget = locked == null || !locked;
            repository.toggleScooter();
        } else if (ACTION_SYNC.equals(action)) {
            monitoringMode = backgroundWanted;
            transientAction = false;
            transientTarget = null;
            if (!currentReady && backgroundWanted) repository.connectIfNeeded();
        } else if (ACTION_MONITOR.equals(action)) {
            monitoringMode = true;
            transientAction = false;
            transientTarget = null;
            repository.connectIfNeeded();
        } else {
            monitoringMode = true;
            transientAction = false;
            transientTarget = null;
            repository.setPersistentEnabled(true);
            repository.connectIfNeeded();
        }

        if (!currentReady) {
            ensureOfflineSince();
            scheduleDisconnectExpiry();
        }
        return START_STICKY;
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) {
        handleChargeAlert(snapshot);

        boolean ready = isReady(snapshot);
        boolean backgroundWanted = snapshot.persistent || snapshot.fullChargeAlert;
        boolean recovered = ready && disconnectedSince != 0L;

        // Self-heal a service that survived while its foreground notification did not. This can
        // happen in the narrow stopSelf/onDestroy window or after a failed foreground start. If the
        // repository is already READY, republish the current snapshot immediately.
        if (ready && backgroundWanted && !foregroundStarted) {
            startForeground(NOTIFICATION_ID, buildNotification(snapshot));
            foregroundStarted = true;
            foregroundReady = true;
            monitoringMode = true;
            recovered = true;
        }

        if (ready) {
            clearOfflineSince();
            main.removeCallbacks(disconnectExpiryRunnable);
        }

        // The old implementation only started this timer if this exact Service instance remembered
        // a prior READY connection. After Service recreation that flag was false, so an offline FGS
        // could live forever. Any foreground offline notification now gets the same persisted clock.
        if (!ready && monitoringMode && foregroundStarted) {
            ensureOfflineSince();
            if (offlineGraceExpired()) {
                expireOfflineNotification();
                return;
            }
            scheduleDisconnectExpiry();
            queueNotification(snapshot, true);
            return;
        }

        // Telemetry churn is throttled, but a reconnect transition is user-visible state and
        // should replace a stale red reconnect label immediately.
        if (foregroundStarted && ready) queueNotification(snapshot, recovered);

        if (transientAction && transientTarget != null
                && transientTarget.equals(snapshot.telemetry.getLocked())) {
            transientAction = false;
            transientTarget = null;
            main.postDelayed(() -> {
                if (!repository.isPersistentEnabled() && !repository.isFullChargeAlertEnabled()) {
                    repository.disconnect();
                    if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE);
                    foregroundStarted = false;
                    foregroundReady = false;
                    stopSelf();
                }
            }, 500);
        }
    }

    private void scheduleDisconnectExpiry() {
        main.removeCallbacks(disconnectExpiryRunnable);
        long since = ensureOfflineSince();
        long remaining = Math.max(0L,
                since + OFFLINE_NOTIFICATION_GRACE_MS - System.currentTimeMillis());
        main.postDelayed(disconnectExpiryRunnable, remaining);
    }

    private boolean offlineGraceExpired() {
        long since = ensureOfflineSince();
        return System.currentTimeMillis() - since >= OFFLINE_NOTIFICATION_GRACE_MS;
    }

    private long ensureOfflineSince() {
        if (disconnectedSince > 0L) return disconnectedSince;
        SharedPreferences prefs = offlineStatePrefs();
        long now = System.currentTimeMillis();
        long stored = prefs.getLong(PREF_OFFLINE_SINCE, 0L);
        if (stored <= 0L || stored > now) {
            stored = now;
            prefs.edit().putLong(PREF_OFFLINE_SINCE, stored).apply();
        }
        disconnectedSince = stored;
        return stored;
    }

    private void clearOfflineSince() {
        disconnectedSince = 0L;
        offlineStatePrefs().edit().remove(PREF_OFFLINE_SINCE).apply();
    }

    private SharedPreferences offlineStatePrefs() {
        return getSharedPreferences(SERVICE_PREFS, Context.MODE_PRIVATE);
    }

    private void expireOfflineNotification() {
        pendingSnapshot = null;
        monitoringMode = false;
        main.removeCallbacks(notificationRunnable);
        main.removeCallbacks(disconnectExpiryRunnable);
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE);
        foregroundStarted = false;
        foregroundReady = false;
        stopSelf();
    }

    private void queueNotification(ScooterRepository.Snapshot snapshot, boolean immediate) {
        pendingSnapshot = snapshot;
        long elapsed = System.currentTimeMillis() - lastNotificationAt;
        main.removeCallbacks(notificationRunnable);
        if (immediate || elapsed >= NOTIFICATION_THROTTLE_MS) main.post(notificationRunnable);
        else main.postDelayed(notificationRunnable, NOTIFICATION_THROTTLE_MS - elapsed);
    }

    private Notification buildNotification(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        boolean ready = isReady(snapshot);
        String title = notificationTitle(snapshot);
        String text = notificationText(snapshot);
        String actionLabel = ready
                ? (Boolean.TRUE.equals(t.getLocked()) ? "🔓 UNLOCK" : "🔐 LOCK")
                : "↻ RECONNECT";

        Intent contentIntent = new Intent(this, BootstrapActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 10, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent action = servicePendingIntent(ready ? ACTION_TOGGLE : ACTION_MONITOR, 11);

        RemoteViews compact = new RemoteViews(getPackageName(), R.layout.notification_scooter);
        compact.setTextViewText(R.id.notification_title, title);
        compact.setTextViewText(R.id.notification_text, text);
        Integer rssi = ready ? t.getRssi() : null;
        compact.setTextViewText(R.id.notification_signal, BleSignal.bars(rssi));
        compact.setTextColor(R.id.notification_signal,
                rssi == null ? SIGNAL_MUTED : (BleSignal.weak(rssi) ? SIGNAL_RED : SIGNAL_GREEN));
        compact.setTextViewText(R.id.notification_lock_action, actionLabel);
        compact.setOnClickPendingIntent(R.id.notification_lock_action, action);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, LIVE_CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_shortcut_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setCustomContentView(compact)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                // While connected it is a true persistent control. Offline it is dismissible;
                // ACTION_DISMISS_OFFLINE stops this FGS so the app does not immediately repost it.
                .setOngoing(ready)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH);
        if (!ready) builder.setDeleteIntent(servicePendingIntent(ACTION_DISMISS_OFFLINE, 12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private String notificationTitle(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry telemetry = snapshot.telemetry;
        StringBuilder out = new StringBuilder();
        if (telemetry.isCharging()) {
            boolean alt = ((System.currentTimeMillis() / 1500L) & 1L) == 0L;
            out.append(alt ? "🔋" : "⚡");
        } else {
            out.append("🛴");
        }
        Integer battery = telemetry.getBatteryPercent();
        if (battery != null) out.append(' ').append(battery).append('%');
        double rideKm = snapshot.rideStats == null ? 0.0 : snapshot.rideStats.currentDistanceKm();
        out.append(" · ").append(String.format(Locale.US, "%.1f km", rideKm));
        return out.toString();
    }

    private String notificationText(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        boolean ready = isReady(snapshot);
        if (ready) {
            String speed = t.getSpeed() == null ? "— km/h"
                    : String.format(Locale.US, "%.1f km/h", t.getSpeed());
            return t.isCharging() ? "Charging · " + speed : speed + " · 🟢 Connected";
        }
        if (snapshot.connectionState == ScooterConnectionState.RECONNECTING
                || snapshot.connectionState == ScooterConnectionState.CONNECTING
                || snapshot.connectionState == ScooterConnectionState.SCANNING
                || snapshot.connectionState == ScooterConnectionState.CONNECTED) {
            return "🔴 Reconnecting";
        }
        return "🔴 Disconnected";
    }

    private static boolean isReady(ScooterRepository.Snapshot snapshot) {
        return snapshot != null && snapshot.connectionState == ScooterConnectionState.READY
                && snapshot.telemetry != null && snapshot.telemetry.isConnected();
    }

    private void handleChargeAlert(ScooterRepository.Snapshot snapshot) {
        if (!snapshot.fullChargeAlert) {
            resetChargeTracking();
            return;
        }
        ScooterTelemetry telemetry = snapshot.telemetry;
        if (telemetry == null || !telemetry.isConnected()) return;

        Integer battery = telemetry.getBatteryPercent();
        if (battery == null) return;

        long now = System.currentTimeMillis();
        Double speed = telemetry.getSpeed();
        Double current = telemetry.getBatteryCurrent();
        boolean moving = speed != null && Math.abs(speed) >= ChargingDetector.CLEAR_MOVING_KMH;
        boolean discharging = current != null && current < -ChargingDetector.START_CURRENT_A;

        if (battery <= 95) {
            fullChargeAlertSent = false;
            clearChargeSession();
        }

        // Riding or real discharge invalidates any previously observed charging session. This
        // prevents a stale 99 -> 100 SOC update after unplugging from firing while on the road.
        if (moving || discharging) clearChargeSession();

        // Only the BMS/current-based detector is allowed to establish a charge session. A bare
        // percentage increase is not enough: SOC can jump after reconnects or regenerative braking.
        if (telemetry.isCharging()) {
            chargeSessionObserved = true;
            lastConfirmedChargingAt = now;
        }

        boolean recentCharge = chargeSessionObserved
                && lastConfirmedChargingAt > 0L
                && Math.max(0L, now - lastConfirmedChargingAt) <= FULL_CHARGE_SESSION_GRACE_MS;
        if (chargeSessionObserved && !telemetry.isCharging() && !recentCharge) {
            clearChargeSession();
            recentCharge = false;
        }

        // Require observable SOC progress during the confirmed/recent charging session. This also
        // means merely connecting to a scooter that already reports 100% cannot trigger an alert.
        if (lastBatteryPercent != null && battery > lastBatteryPercent && recentCharge) {
            chargeProgressObserved = true;
        }

        if (battery >= 100 && recentCharge && chargeProgressObserved && !fullChargeAlertSent) {
            fullChargeAlertSent = true;
            showFullChargeNotification(snapshot.modelName);
        }
        lastBatteryPercent = battery;
    }

    private void clearChargeSession() {
        chargeSessionObserved = false;
        chargeProgressObserved = false;
        lastConfirmedChargingAt = 0L;
    }

    private void resetChargeTracking() {
        lastBatteryPercent = null;
        fullChargeAlertSent = false;
        clearChargeSession();
    }

    private void showFullChargeNotification(String modelName) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        String model = modelName == null ? "Scooter" : modelName;
        PendingIntent content = PendingIntent.getActivity(this, 20,
                new Intent(this, BootstrapActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHARGE_CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_shortcut_lock)
                .setContentTitle("🔋 Scooter fully charged")
                .setContentText(model + " reached 100%")
                .setContentIntent(content)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_MAX);
        manager.notify(CHARGE_NOTIFICATION_ID, builder.build());
        ChargeChime.play();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, ScooterService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void ensureNotificationChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel live = new NotificationChannel(
                LIVE_CHANNEL_ID, "Scooter live status", NotificationManager.IMPORTANCE_HIGH);
        live.setDescription("Live battery, charging, BLE signal and ride status with quick lock control");
        live.setShowBadge(false);
        live.setSound(null, null);
        live.enableVibration(false);
        live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(live);

        // The notification channel stays silent because the app emits its own generated chime.
        NotificationChannel charge = new NotificationChannel(
                CHARGE_CHANNEL_ID, "Full charge alerts", NotificationManager.IMPORTANCE_HIGH);
        charge.setDescription("Alert when the scooter battery reaches 100% while charging");
        charge.setSound(null, null);
        charge.enableVibration(false);
        charge.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(charge);

        manager.deleteNotificationChannel("scooter_connection");
        manager.deleteNotificationChannel("scooter_charge_alerts");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        running = false;
        foregroundReady = false;
        main.removeCallbacks(notificationRunnable);
        main.removeCallbacks(disconnectExpiryRunnable);
        if (repository != null) repository.removeListener(this);
        super.onDestroy();
    }
}
