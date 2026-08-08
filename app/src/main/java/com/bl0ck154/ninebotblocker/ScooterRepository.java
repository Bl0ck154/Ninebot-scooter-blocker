package com.bl0ck154.ninebotblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArrayList;

/** Single source of truth for identity, connection, commands, reconnect and telemetry. */
public final class ScooterRepository implements ScooterBleManager.Listener {
    public static final String PREFS = "ninebot_quick_lock";
    public static final String PREF_ADDRESS = "address";
    public static final String PREF_NAME = "name";
    public static final String PREF_SERIAL = "serial";
    public static final String PREF_LOCK_KNOWN = "lock_state_known";
    public static final String PREF_LOCK_STATE = "lock_state";
    public static final String PREF_AUTO_CONNECT = "auto_connect";
    public static final String PREF_PERSISTENT = "persistent_notification";
    public static final String PREF_FULL_CHARGE_ALERT = "full_charge_alert";

    public interface Listener { void onSnapshot(Snapshot snapshot); }
    public interface DiscoveryListener {
        void onDevice(String address, String name, int rssi);
        void onFinished();
        void onError(String message);
    }

    public static final class Snapshot {
        public final ScooterConnectionState connectionState;
        public final ScooterTelemetry telemetry;
        public final String deviceName;
        public final String modelName;
        public final String address;
        public final String status;
        public final boolean autoConnect;
        public final boolean persistent;
        public final boolean fullChargeAlert;

        Snapshot(ScooterConnectionState state, ScooterTelemetry telemetry, String name,
                 String modelName, String address, String status, boolean autoConnect,
                 boolean persistent, boolean fullChargeAlert) {
            this.connectionState = state;
            this.telemetry = telemetry;
            this.deviceName = name;
            this.modelName = modelName;
            this.address = address;
            this.status = status;
            this.autoConnect = autoConnect;
            this.persistent = persistent;
            this.fullChargeAlert = fullChargeAlert;
        }
    }

    private static volatile ScooterRepository instance;
    public static ScooterRepository get(Context context) {
        if (instance == null) {
            synchronized (ScooterRepository.class) {
                if (instance == null) instance = new ScooterRepository(context.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScooterBleManager ble;
    private final ScooterTelemetry telemetry = new ScooterTelemetry();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long[] reconnectDelays = {1000, 2000, 5000, 10000, 30000};

    private ScooterConnectionState state = ScooterConnectionState.DISCONNECTED;
    private String status = "Disconnected";
    private boolean uiActive;
    private boolean identityVerified;
    private int reconnectAttempt;
    private int pollTick;
    private int fastPollIndex;
    private int slowPollIndex;
    private Boolean pendingDesiredLock;

    private final Runnable reconnectRunnable = this::runReconnectAttempt;
    private final Runnable pollRunnable = this::pollOnce;

    private ScooterRepository(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ble = new ScooterBleManager(appContext, this);
        if (prefs.getBoolean(PREF_LOCK_KNOWN, false)) {
            telemetry.setLocked(prefs.getBoolean(PREF_LOCK_STATE, false));
        }
    }

    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        listener.onSnapshot(snapshot());
    }

    public void removeListener(Listener listener) { listeners.remove(listener); }

    public Snapshot snapshot() {
        String name = deviceName();
        return new Snapshot(state, telemetry.copy(), name,
                ScooterIdentity.displayModel(name, prefs.getString(PREF_SERIAL, null)),
                address(), status, isAutoConnectEnabled(), isPersistentEnabled(),
                isFullChargeAlertEnabled());
    }

    public boolean hasRememberedScooter() { return address() != null; }
    public boolean isAutoConnectEnabled() { return prefs.getBoolean(PREF_AUTO_CONNECT, true); }
    public boolean isPersistentEnabled() { return prefs.getBoolean(PREF_PERSISTENT, false); }
    public boolean isFullChargeAlertEnabled() { return prefs.getBoolean(PREF_FULL_CHARGE_ALERT, false); }
    public String address() { return prefs.getString(PREF_ADDRESS, null); }

    public String deviceName() {
        String value = prefs.getString(PREF_NAME, null);
        return value == null || value.trim().isEmpty() ? "Ninebot / Segway scooter" : value;
    }

    public void setUiActive(boolean active) {
        uiActive = active;
        if (active) {
            if (isAutoConnectEnabled()) connectIfNeeded();
        } else if (!isPersistentEnabled() && !isFullChargeAlertEnabled()) {
            stopPolling();
            ble.disconnectSilently();
            telemetry.setConnected(false);
            setState(ScooterConnectionState.DISCONNECTED, "Disconnected");
        }
    }

    public void setAutoConnectEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_AUTO_CONNECT, enabled).apply();
        if (enabled) connectIfNeeded();
        else cancelReconnect();
        notifyListeners();
    }

    public void setPersistentEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_PERSISTENT, enabled).apply();
        if (enabled) connectIfNeeded();
        else if (!uiActive && !isFullChargeAlertEnabled()) disconnectWhenIdle();
        notifyListeners();
    }

    public void setFullChargeAlertEnabled(boolean enabled) {
        prefs.edit().putBoolean(PREF_FULL_CHARGE_ALERT, enabled).apply();
        if (enabled) connectIfNeeded();
        else if (!uiActive && !isPersistentEnabled()) disconnectWhenIdle();
        notifyListeners();
    }

    private void disconnectWhenIdle() {
        cancelReconnect();
        stopPolling();
        ble.disconnectSilently();
        telemetry.setConnected(false);
        setState(ScooterConnectionState.DISCONNECTED, "Disconnected");
    }

    public void connectIfNeeded() {
        if (!hasRememberedScooter() || ble.isReady()
                || state == ScooterConnectionState.CONNECTING
                || state == ScooterConnectionState.CONNECTED
                || state == ScooterConnectionState.RECONNECTING) return;
        identityVerified = false;
        ble.connect(address(), prefs.getString(PREF_NAME, null), false);
    }

    public void disconnect() {
        cancelReconnect();
        stopPolling();
        identityVerified = false;
        telemetry.setConnected(false);
        ble.disconnectSilently();
        setState(ScooterConnectionState.DISCONNECTED, "Disconnected");
    }

    public void lockScooter() { requestLocked(true); }
    public void unlockScooter() { requestLocked(false); }

    public void toggleScooter() {
        Boolean locked = telemetry.getLocked();
        requestLocked(locked == null || !locked);
    }

    private void requestLocked(boolean locked) {
        if (!hasRememberedScooter()) {
            setState(ScooterConnectionState.ERROR, "Select your scooter first");
            return;
        }
        pendingDesiredLock = locked;
        status = locked ? "Lock queued…" : "Unlock queued…";
        notifyListeners();
        if (identityVerified && ble.isReady()) sendPendingAction();
        else connectIfNeeded();
    }

    private void sendPendingAction() {
        if (!identityVerified || pendingDesiredLock == null || !ble.isReady()) return;
        boolean desired = pendingDesiredLock;
        pendingDesiredLock = null;
        status = desired ? "Locking…" : "Unlocking…";
        notifyListeners();
        if (!ble.setLocked(desired)) {
            pendingDesiredLock = desired;
            scheduleReconnect();
        }
    }

    public void startDiscovery(DiscoveryListener listener) {
        if (listener == null) return;
        cancelReconnect();
        stopPolling();
        ble.disconnectSilently();
        identityVerified = false;
        ble.startScan(false, 10000, new ScooterBleManager.ScanListener() {
            @Override public void onDeviceFound(String address, String name, int rssi) {
                listener.onDevice(address, name, rssi);
            }
            @Override public void onScanFinished() {
                setState(ScooterConnectionState.DISCONNECTED, "Search finished");
                listener.onFinished();
            }
            @Override public void onScanError(String message) {
                setState(ScooterConnectionState.ERROR, message);
                listener.onError(message);
            }
        });
    }

    public void stopDiscovery() { ble.stopScan(); }

    public void selectScooter(String newAddress, String newName) {
        ble.stopScan();
        cancelReconnect();
        stopPolling();
        identityVerified = false;
        pendingDesiredLock = null;
        telemetry.setLocked(null);
        prefs.edit()
                .putString(PREF_ADDRESS, newAddress)
                .putString(PREF_NAME, newName == null ? "" : newName)
                .remove(PREF_SERIAL)
                .remove(PREF_LOCK_KNOWN)
                .remove(PREF_LOCK_STATE)
                .apply();
        ble.connect(newAddress, newName, false);
        notifyListeners();
    }

    @Override public void onConnectionState(ScooterConnectionState newState, String message) {
        setState(newState, message);
    }

    @Override public void onReady(byte[] serialBytes) {
        String incomingSerial = serialString(serialBytes);
        String rememberedSerial = prefs.getString(PREF_SERIAL, null);
        if (rememberedSerial != null && !rememberedSerial.trim().isEmpty()
                && incomingSerial != null && !rememberedSerial.equals(incomingSerial)) {
            identityVerified = false;
            telemetry.setConnected(false);
            ble.disconnectSilently();
            setState(ScooterConnectionState.ERROR,
                    "A different Ninebot answered; reconnecting to your saved scooter");
            scheduleReconnect();
            return;
        }
        if (rememberedSerial == null && incomingSerial != null && !incomingSerial.trim().isEmpty()) {
            prefs.edit().putString(PREF_SERIAL, incomingSerial).apply();
        }
        identityVerified = true;
        reconnectAttempt = 0;
        cancelReconnect();
        telemetry.setConnected(true);
        setState(ScooterConnectionState.READY, "Connected");

        ble.send(G30Protocol.readLockStatus());
        startPolling();
        sendPendingAction();

        if (isPersistentEnabled() || isFullChargeAlertEnabled()) {
            ScooterService.startForConnectedScooter(appContext);
        }
    }

    @Override public void onPacket(byte[] packet) {
        if (identityVerified && G30Protocol.applyTelemetryPacket(packet, telemetry)) notifyListeners();
    }

    @Override public void onActionResult(boolean success, Boolean locked, String message) {
        if (success && locked != null) {
            telemetry.setLocked(locked);
            prefs.edit()
                    .putBoolean(PREF_LOCK_KNOWN, true)
                    .putBoolean(PREF_LOCK_STATE, locked)
                    .apply();
        }
        status = message == null ? (success ? "Done" : "Command failed") : message;
        notifyListeners();
    }

    @Override public void onDisconnected(String reason) {
        identityVerified = false;
        telemetry.setConnected(false);
        stopPolling();
        status = reason == null ? "Disconnected" : reason;
        if (shouldStayConnected() && isAutoConnectEnabled() && hasRememberedScooter()) {
            scheduleReconnect();
        } else {
            setState(ScooterConnectionState.DISCONNECTED, status);
        }
    }

    private boolean shouldStayConnected() {
        return uiActive || isPersistentEnabled() || isFullChargeAlertEnabled() || pendingDesiredLock != null;
    }

    private void scheduleReconnect() {
        if (!shouldStayConnected() || !isAutoConnectEnabled() || !hasRememberedScooter()) return;
        stopPolling();
        main.removeCallbacks(reconnectRunnable);
        int index = Math.min(reconnectAttempt, reconnectDelays.length - 1);
        long delay = reconnectDelays[index];
        reconnectAttempt++;
        setState(ScooterConnectionState.RECONNECTING, "Reconnecting…");
        main.postDelayed(reconnectRunnable, delay);
    }

    private void runReconnectAttempt() {
        if (!shouldStayConnected() || !isAutoConnectEnabled() || !hasRememberedScooter()) return;
        final String savedAddress = address();
        final String savedName = prefs.getString(PREF_NAME, null);
        if (reconnectAttempt % 3 == 0) {
            identityVerified = false;
            ble.connect(savedAddress, savedName, true);
            return;
        }
        ble.startScan(true, 8000, new ScooterBleManager.ScanListener() {
            private boolean matched;
            @Override public void onDeviceFound(String foundAddress, String foundName, int rssi) {
                if (matched) return;
                boolean addressMatch = savedAddress != null && savedAddress.equalsIgnoreCase(foundAddress);
                boolean nameMatch = savedName != null && !savedName.trim().isEmpty()
                        && savedName.equals(foundName);
                if (!addressMatch && !nameMatch) return;
                matched = true;
                ble.stopScan();
                identityVerified = false;
                ble.connect(foundAddress, foundName, true);
            }
            @Override public void onScanFinished() { if (!matched) scheduleReconnect(); }
            @Override public void onScanError(String message) { if (!matched) scheduleReconnect(); }
        });
    }

    private void cancelReconnect() { main.removeCallbacks(reconnectRunnable); }

    private void startPolling() {
        stopPolling();
        pollTick = 0;
        fastPollIndex = 0;
        slowPollIndex = 0;
        main.postDelayed(pollRunnable, 250L);
    }

    private void stopPolling() { main.removeCallbacks(pollRunnable); }

    private void pollOnce() {
        if (!identityVerified || !ble.isReady()) return;
        byte[] packet;
        pollTick++;
        if (pollTick % 8 == 0) {
            switch (slowPollIndex++ % 5) {
                case 0: packet = G30Protocol.readRemainingRange(); break;
                case 1: packet = G30Protocol.readTripDistance(); break;
                case 2: packet = G30Protocol.readOdometer(); break;
                case 3: packet = G30Protocol.readControllerTemperature(); break;
                default: packet = G30Protocol.readBatteryTemperature(); break;
            }
        } else {
            switch (fastPollIndex++ % 5) {
                case 0: packet = G30Protocol.readBatteryPercent(); break;
                case 1: packet = G30Protocol.readSpeed(); break;
                case 2: packet = G30Protocol.readBatteryCurrent(); break;
                case 3: packet = G30Protocol.readBatteryVoltage(); break;
                default: packet = G30Protocol.readLockStatus(); break;
            }
        }
        ble.send(packet);
        Double speed = telemetry.getSpeed();
        main.postDelayed(pollRunnable, speed != null && speed > 1.0 ? 500L : 1000L);
    }

    private void setState(ScooterConnectionState newState, String message) {
        state = newState;
        if (message != null && !message.trim().isEmpty()) status = message;
        notifyListeners();
    }

    private void notifyListeners() {
        Snapshot snapshot = snapshot();
        for (Listener listener : listeners) listener.onSnapshot(snapshot);
    }

    private static String serialString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        String value = new String(Arrays.copyOf(bytes, bytes.length), StandardCharsets.US_ASCII)
                .replace("\u0000", "").trim();
        return value.isEmpty() ? null : value;
    }
}
