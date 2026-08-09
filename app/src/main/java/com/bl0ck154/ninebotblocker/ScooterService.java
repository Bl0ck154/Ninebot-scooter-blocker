package com.bl0ck154.ninebotblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.Locale;

/** Foreground owner for live scooter notification and full-charge monitoring. */
public final class ScooterService extends Service implements ScooterRepository.Listener {
    public static final String ACTION_START = "com.bl0ck154.ninebotblocker.START";
    public static final String ACTION_MONITOR = "com.bl0ck154.ninebotblocker.MONITOR";
    public static final String ACTION_STOP_NOTIFICATION = "com.bl0ck154.ninebotblocker.STOP_NOTIFICATION";
    public static final String ACTION_TOGGLE = "com.bl0ck154.ninebotblocker.TOGGLE";

    public static final String LIVE_CHANNEL_ID = "scooter_live_priority_v2";
    public static final String CHARGE_CHANNEL_ID = "scooter_charge_alerts_v2";

    private static final int NOTIFICATION_ID = 15430;
    private static final int CHARGE_NOTIFICATION_ID = 15431;
    private static final long NOTIFICATION_THROTTLE_MS = 1500;
    private static final int SIGNAL_GREEN = 0xFF007E79;
    private static final int SIGNAL_RED = 0xFFB42318;
    private static final int SIGNAL_MUTED = 0xFF5F6368;
    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScooterRepository repository;
    private ScooterRepository.Snapshot pendingSnapshot;
    private long lastNotificationAt;
    private long disconnectedSince;
    private boolean transientAction;
    private Boolean transientTarget;
    private boolean monitoringMode;
    private boolean foregroundStarted;
    private boolean hadReadyConnection;

    private Integer lastBatteryPercent;
    private boolean chargeSessionObserved;
    private boolean fullChargeAlertSent;

    private final Runnable notificationRunnable = () -> {
        ScooterRepository.Snapshot snapshot = pendingSnapshot;
        pendingSnapshot = null;
        if (snapshot == null || !foregroundStarted) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(snapshot));
        lastNotificationAt = System.currentTimeMillis();
    };

    private final Runnable disconnectExpiryRunnable = new Runnable() {
        @Override public void run() {
            if (!foregroundStarted || disconnectedSince == 0L || repository == null) return;
            ScooterRepository.Snapshot current = repository.snapshot();
            boolean ready = current.connectionState == ScooterConnectionState.READY
                    && current.telemetry.isConnected();
            if (ready) {
                disconnectedSince = 0L;
                return;
            }
            long grace = notificationGraceMs();
            long elapsed = System.currentTimeMillis() - disconnectedSince;
            if (elapsed < grace) {
                main.postDelayed(this, grace - elapsed);
                return;
            }
            pendingSnapshot = null;
            main.removeCallbacks(notificationRunnable);
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
            stopSelf();
        }
    };

    public static boolean isRunning() { return running; }

    public static void startForConnectedScooter(Context context) {
        if (running) return;
        Intent intent = new Intent(context, ScooterService.class).setAction(ACTION_START);
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
            disconnectedSince = 0L;
            pendingSnapshot = null;
            main.removeCallbacks(notificationRunnable);
            main.removeCallbacks(disconnectExpiryRunnable);
            if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }

        ScooterRepository.Snapshot current = repository.snapshot();
        boolean backgroundWanted = repository.isPersistentEnabled() || repository.isFullChargeAlertEnabled();

        if (action == null && !backgroundWanted) {
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean commandAction = ACTION_TOGGLE.equals(action);
        if (!commandAction && !ACTION_MONITOR.equals(action)
                && (current.connectionState != ScooterConnectionState.READY
                || !current.telemetry.isConnected())) {
            if (ACTION_START.equals(action)) repository.setPersistentEnabled(true);
            repository.connectIfNeeded();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(current));
        foregroundStarted = true;
        boolean currentReady = current.connectionState == ScooterConnectionState.READY
                && current.telemetry.isConnected();
        hadReadyConnection = hadReadyConnection || currentReady;

        if (ACTION_TOGGLE.equals(action)) {
            monitoringMode = backgroundWanted;
            transientAction = !backgroundWanted;
            Boolean locked = repository.snapshot().telemetry.getLocked();
            transientTarget = locked == null || !locked;
            repository.toggleScooter();
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
        return START_STICKY;
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) {
        handleChargeAlert(snapshot);

        boolean ready = snapshot.connectionState == ScooterConnectionState.READY
                && snapshot.telemetry.isConnected();
        if (ready) {
            hadReadyConnection = true;
            disconnectedSince = 0L;
            main.removeCallbacks(disconnectExpiryRunnable);
        }

        if (monitoringMode && hadReadyConnection && !ready && foregroundStarted) {
            if (disconnectedSince == 0L) disconnectedSince = System.currentTimeMillis();
            scheduleDisconnectExpiry();
            queueNotification(snapshot, true);
            return;
        }

        if (foregroundStarted && ready) queueNotification(snapshot, false);

        if (transientAction && transientTarget != null
                && transientTarget.equals(snapshot.telemetry.getLocked())) {
            transientAction = false;
            transientTarget = null;
            main.postDelayed(() -> {
                if (!repository.isPersistentEnabled() && !repository.isFullChargeAlertEnabled()) {
                    repository.disconnect();
                    if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE);
                    foregroundStarted = false;
                    stopSelf();
                }
            }, 500);
        }
    }

    private void scheduleDisconnectExpiry() {
        main.removeCallbacks(disconnectExpiryRunnable);
        long remaining = Math.max(0L,
                disconnectedSince + notificationGraceMs() - System.currentTimeMillis());
        main.postDelayed(disconnectExpiryRunnable, remaining);
    }

    private long notificationGraceMs() {
        return repository == null ? 20L * 60L * 1000L : repository.getRidePauseTimeoutMs();
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
        boolean ready = snapshot.connectionState == ScooterConnectionState.READY && t.isConnected();
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
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setPriority(Notification.PRIORITY_HIGH);
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
        boolean ready = snapshot.connectionState == ScooterConnectionState.READY && t.isConnected();
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

    private void handleChargeAlert(ScooterRepository.Snapshot snapshot) {
        if (!snapshot.fullChargeAlert) {
            lastBatteryPercent = null;
            chargeSessionObserved = false;
            fullChargeAlertSent = false;
            return;
        }
        if (!snapshot.telemetry.isConnected()) return;

        Integer battery = snapshot.telemetry.getBatteryPercent();
        if (battery == null) return;

        if (battery <= 95) {
            fullChargeAlertSent = false;
            chargeSessionObserved = false;
        }
        if (snapshot.telemetry.isCharging()) chargeSessionObserved = true;
        if (lastBatteryPercent != null && battery > lastBatteryPercent) chargeSessionObserved = true;

        if (battery >= 100 && chargeSessionObserved && !fullChargeAlertSent) {
            fullChargeAlertSent = true;
            showFullChargeNotification(snapshot.modelName);
        }
        lastBatteryPercent = battery;
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
        playChargeBeep();
    }

    /** Short, low-key double beep on the notification audio stream; no bundled audio asset. */
    private void playChargeBeep() {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
            main.postDelayed(() -> {
                try { tone.release(); } catch (RuntimeException ignored) {}
            }, 600L);
        } catch (RuntimeException ignored) {}
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

        // v2 intentionally stays silent: the app emits its own short, consistent double-beep.
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
        main.removeCallbacks(notificationRunnable);
        main.removeCallbacks(disconnectExpiryRunnable);
        if (repository != null) repository.removeListener(this);
        super.onDestroy();
    }
}
