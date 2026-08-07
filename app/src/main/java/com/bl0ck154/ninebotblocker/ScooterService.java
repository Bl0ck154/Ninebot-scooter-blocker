package com.bl0ck154.ninebotblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Locale;

/** Foreground owner for long-running G30 connection, reconnect and notification. */
public final class ScooterService extends Service implements ScooterRepository.Listener {
    public static final String ACTION_START = "com.bl0ck154.ninebotblocker.START";
    public static final String ACTION_STOP = "com.bl0ck154.ninebotblocker.STOP";
    public static final String ACTION_LOCK = "com.bl0ck154.ninebotblocker.LOCK";
    public static final String ACTION_UNLOCK = "com.bl0ck154.ninebotblocker.UNLOCK";
    public static final String ACTION_TOGGLE = "com.bl0ck154.ninebotblocker.TOGGLE";

    private static final String CHANNEL_ID = "scooter_connection";
    private static final int NOTIFICATION_ID = 15430;
    private static final long NOTIFICATION_THROTTLE_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScooterRepository repository;
    private ScooterRepository.Snapshot pendingSnapshot;
    private long lastNotificationAt;
    private boolean transientAction;
    private Boolean transientTarget;

    private final Runnable notificationRunnable = () -> {
        ScooterRepository.Snapshot snapshot = pendingSnapshot;
        pendingSnapshot = null;
        if (snapshot == null) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(snapshot));
        lastNotificationAt = System.currentTimeMillis();
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        repository = ScooterRepository.get(this);
        repository.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null && !repository.isPersistentEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(repository.snapshot()));

        if (ACTION_STOP.equals(action)) {
            repository.setPersistentEnabled(false);
            repository.disconnect();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_LOCK.equals(action)) {
            transientAction = !repository.isPersistentEnabled();
            transientTarget = true;
            repository.lockScooter();
        } else if (ACTION_UNLOCK.equals(action)) {
            transientAction = !repository.isPersistentEnabled();
            transientTarget = false;
            repository.unlockScooter();
        } else if (ACTION_TOGGLE.equals(action)) {
            transientAction = !repository.isPersistentEnabled();
            Boolean locked = repository.snapshot().telemetry.getLocked();
            transientTarget = locked == null || !locked;
            repository.toggleScooter();
        } else {
            transientAction = false;
            transientTarget = null;
            repository.setPersistentEnabled(true);
            repository.connectIfNeeded();
        }
        return START_STICKY;
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) {
        pendingSnapshot = snapshot;
        long elapsed = System.currentTimeMillis() - lastNotificationAt;
        main.removeCallbacks(notificationRunnable);
        if (elapsed >= NOTIFICATION_THROTTLE_MS) main.post(notificationRunnable);
        else main.postDelayed(notificationRunnable, NOTIFICATION_THROTTLE_MS - elapsed);

        if (transientAction && transientTarget != null
                && transientTarget.equals(snapshot.telemetry.getLocked())) {
            transientAction = false;
            transientTarget = null;
            main.postDelayed(() -> {
                if (!repository.isPersistentEnabled()) {
                    repository.disconnect();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }, 500);
        }
    }

    private Notification buildNotification(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        String title = t.getBatteryPercent() == null
                ? "🛴 Ninebot Max"
                : "🛴 Ninebot Max — " + t.getBatteryPercent() + "%";
        String text = notificationText(snapshot);

        Intent contentIntent = new Intent(this, BootstrapActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 10, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_shortcut_lock)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(null, "LOCK",
                        servicePendingIntent(ACTION_LOCK, 11)).build())
                .addAction(new Notification.Action.Builder(null, "UNLOCK",
                        servicePendingIntent(ACTION_UNLOCK, 12)).build())
                .addAction(new Notification.Action.Builder(null, "DISCONNECT",
                        servicePendingIntent(ACTION_STOP, 13)).build());
        return builder.build();
    }

    private String notificationText(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        Integer battery = t.getBatteryPercent();
        Double speed = t.getSpeed();
        Double range = t.getRemainingRange();
        Double trip = t.getTripDistance();
        Double voltage = t.getBatteryVoltage();

        if (speed != null && speed >= 1.0) {
            StringBuilder out = new StringBuilder();
            if (battery != null) out.append(battery).append("% · ");
            out.append(String.format(Locale.US, "%.1f km/h", speed));
            if (range != null) out.append(String.format(Locale.US, " · %.1f km range", range));
            return out.toString();
        }
        if (range != null && trip != null) {
            return String.format(Locale.US, "%.1f km range · %.1f km trip", range, trip);
        }
        if (voltage != null && trip != null) {
            return String.format(Locale.US, "%.1f V · %.1f km trip", voltage, trip);
        }
        return snapshot.status == null ? snapshot.connectionState.name() : snapshot.status;
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, ScooterService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Ninebot connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Live Ninebot Max battery and ride telemetry");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        main.removeCallbacks(notificationRunnable);
        if (repository != null) repository.removeListener(this);
        super.onDestroy();
    }
}
