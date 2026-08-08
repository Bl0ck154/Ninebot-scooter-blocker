package com.bl0ck154.ninebotblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.RemoteViews;

import java.util.Locale;

/** Foreground owner for long-running scooter connection, reconnect and notifications. */
public final class ScooterService extends Service implements ScooterRepository.Listener {
    public static final String ACTION_START = "com.bl0ck154.ninebotblocker.START";
    public static final String ACTION_MONITOR = "com.bl0ck154.ninebotblocker.MONITOR";
    public static final String ACTION_STOP_NOTIFICATION = "com.bl0ck154.ninebotblocker.STOP_NOTIFICATION";
    public static final String ACTION_STOP = "com.bl0ck154.ninebotblocker.STOP";
    public static final String ACTION_LOCK = "com.bl0ck154.ninebotblocker.LOCK";
    public static final String ACTION_UNLOCK = "com.bl0ck154.ninebotblocker.UNLOCK";
    public static final String ACTION_TOGGLE = "com.bl0ck154.ninebotblocker.TOGGLE";

    public static final String LIVE_CHANNEL_ID = "scooter_live_priority_v2";
    public static final String CHARGE_CHANNEL_ID = "scooter_charge_alerts";

    private static final int NOTIFICATION_ID = 15430;
    private static final int CHARGE_NOTIFICATION_ID = 15431;
    private static final long NOTIFICATION_THROTTLE_MS = 1500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ScooterRepository repository;
    private ScooterRepository.Snapshot pendingSnapshot;
    private long lastNotificationAt;
    private boolean transientAction;
    private Boolean transientTarget;

    private Integer lastBatteryPercent;
    private boolean chargeRiseObserved;
    private boolean fullChargeAlertSent;

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
        ensureNotificationChannels(this);
        repository = ScooterRepository.get(this);
        repository.addListener(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP_NOTIFICATION.equals(action)) {
            repository.setPersistentEnabled(false);
            if (repository.isFullChargeAlertEnabled()) {
                // Charge monitoring still needs a foreground service on modern Android.
                // Keep the service/connection alive instead of briefly dropping out of FGS state.
                startForeground(NOTIFICATION_ID, buildNotification(repository.snapshot()));
                repository.connectIfNeeded();
                return START_STICKY;
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (action == null && !repository.isPersistentEnabled() && !repository.isFullChargeAlertEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification(repository.snapshot()));

        if (ACTION_STOP.equals(action)) {
            repository.setPersistentEnabled(false);
            repository.setFullChargeAlertEnabled(false);
            repository.disconnect();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_LOCK.equals(action)) {
            transientAction = !repository.isPersistentEnabled() && !repository.isFullChargeAlertEnabled();
            transientTarget = true;
            repository.lockScooter();
        } else if (ACTION_UNLOCK.equals(action)) {
            transientAction = !repository.isPersistentEnabled() && !repository.isFullChargeAlertEnabled();
            transientTarget = false;
            repository.unlockScooter();
        } else if (ACTION_TOGGLE.equals(action)) {
            transientAction = !repository.isPersistentEnabled() && !repository.isFullChargeAlertEnabled();
            Boolean locked = repository.snapshot().telemetry.getLocked();
            transientTarget = locked == null || !locked;
            repository.toggleScooter();
        } else if (ACTION_MONITOR.equals(action)) {
            transientAction = false;
            transientTarget = null;
            repository.connectIfNeeded();
        } else {
            transientAction = false;
            transientTarget = null;
            repository.setPersistentEnabled(true);
            repository.connectIfNeeded();
        }
        return START_STICKY;
    }

    @Override public void onSnapshot(ScooterRepository.Snapshot snapshot) {
        handleChargeAlert(snapshot);

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
                if (!repository.isPersistentEnabled() && !repository.isFullChargeAlertEnabled()) {
                    repository.disconnect();
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    stopSelf();
                }
            }, 500);
        }
    }

    private Notification buildNotification(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        String model = snapshot.modelName == null ? "Ninebot / Segway Scooter" : snapshot.modelName;
        String stateEmoji = lockEmoji(t.getLocked());
        String title = t.getBatteryPercent() == null
                ? "🛴 " + model + " · " + stateEmoji
                : "🛴 " + model + " · " + t.getBatteryPercent() + "% · " + stateEmoji;
        String text = notificationText(snapshot);
        String toggleLabel = Boolean.TRUE.equals(t.getLocked()) ? "🔓 UNLOCK" : "🔐 LOCK";

        Intent contentIntent = new Intent(this, BootstrapActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 10, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent toggle = servicePendingIntent(ACTION_TOGGLE, 11);

        RemoteViews compact = new RemoteViews(getPackageName(), R.layout.notification_scooter);
        compact.setTextViewText(R.id.notification_title, title);
        compact.setTextViewText(R.id.notification_text, text);
        compact.setTextViewText(R.id.notification_lock_action, toggleLabel);
        compact.setOnClickPendingIntent(R.id.notification_lock_action, toggle);

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
                .setPriority(Notification.PRIORITY_HIGH)
                .addAction(new Notification.Action.Builder(null, toggleLabel, toggle).build())
                .addAction(new Notification.Action.Builder(null, "DISCONNECT",
                        servicePendingIntent(ACTION_STOP, 13)).build());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private String notificationText(ScooterRepository.Snapshot snapshot) {
        ScooterTelemetry t = snapshot.telemetry;
        Double speed = t.getSpeed();
        Double range = t.getRemainingRange();
        Double trip = t.getTripDistance();
        Double voltage = t.getBatteryVoltage();

        String base;
        if (speed != null && speed >= 1.0) {
            base = String.format(Locale.US, "%.1f km/h", speed);
            if (range != null) base += String.format(Locale.US, " · %.1f km range", range);
        } else if (range != null && trip != null) {
            base = String.format(Locale.US, "%.1f km range · %.1f km trip", range, trip);
        } else if (voltage != null && trip != null) {
            base = String.format(Locale.US, "%.1f V · %.1f km trip", voltage, trip);
        } else {
            base = snapshot.status == null ? snapshot.connectionState.name() : snapshot.status;
        }
        return base + " · " + lockText(t.getLocked());
    }

    private void handleChargeAlert(ScooterRepository.Snapshot snapshot) {
        if (!snapshot.fullChargeAlert || !snapshot.telemetry.isConnected()) {
            if (!snapshot.fullChargeAlert) {
                lastBatteryPercent = null;
                chargeRiseObserved = false;
                fullChargeAlertSent = false;
            }
            return;
        }

        Integer battery = snapshot.telemetry.getBatteryPercent();
        if (battery == null) return;

        if (battery <= 95) {
            fullChargeAlertSent = false;
            chargeRiseObserved = false;
        }
        if (lastBatteryPercent != null && battery > lastBatteryPercent) chargeRiseObserved = true;

        if (battery >= 100 && chargeRiseObserved && !fullChargeAlertSent) {
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
    }

    private static String lockEmoji(Boolean locked) {
        if (Boolean.TRUE.equals(locked)) return "🔐";
        if (Boolean.FALSE.equals(locked)) return "🔓";
        return "🔏";
    }

    private static String lockText(Boolean locked) {
        if (Boolean.TRUE.equals(locked)) return "🔐 Locked";
        if (Boolean.FALSE.equals(locked)) return "🔓 Unlocked";
        return "🔏 Checking lock";
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
        live.setDescription("Live battery, ride and lock status with quick lock control");
        live.setShowBadge(false);
        live.setSound(null, null);
        live.enableVibration(false);
        live.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(live);

        NotificationChannel charge = new NotificationChannel(
                CHARGE_CHANNEL_ID, "Full charge alerts", NotificationManager.IMPORTANCE_HIGH);
        charge.setDescription("Sound alert when the scooter battery reaches 100%");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build();
        charge.setSound(sound, attrs);
        charge.enableVibration(true);
        charge.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        manager.createNotificationChannel(charge);

        // Remove the old low-priority live channel after migrating to the new channel id.
        manager.deleteNotificationChannel("scooter_connection");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        main.removeCallbacks(notificationRunnable);
        if (repository != null) repository.removeListener(this);
        super.onDestroy();
    }
}
