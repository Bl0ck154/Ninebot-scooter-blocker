package com.bl0ck154.ninebotblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Single source of truth for identity, connection, commands, reconnect, telemetry and ride stats. */
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

    private static final long RECONNECT_WATCHDOG_MS = 12000L;

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
        public final RideStatsTracker.Snapshot rideStats;

        Snapshot(ScooterConnectionState state, ScooterTelemetry telemetry, String name,
                 String modelName, String address, String status, boolean autoConnect,
                 boolean persistent, boolean fullChargeAlert, RideStatsTracker.Snapshot rideStats) {
            this.connectionState = state;
            this.telemetry = telemetry;
            this.deviceName = name;
            this.modelName = modelName;
            this.address = address;
            this.status = status;
            this.autoConnect = autoConnect;
            this.persistent = persistent;
            this.fullChargeAlert = fullChargeAlert;
            this.rideStats = rideStats;
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
    private final ChargingDetector chargingDetector = new ChargingDetector();
    private final RideStatsTracker rideStats;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long[] reconnectDelays = {1000, 2000, 5000, 10000, 30000};

    private ScooterConnectionState state = ScooterConnectionState.DISCONNECTED;
    private String status = "Disconnected";
    private boolean uiActive;
    private int uiActiveClients;
    private boolean identityVerified;
    private int reconnectAttempt;
    private int pollTick;
    private int fastPollIndex;
    private int slowPollIndex;
    private int startupPollIndex;
    private Boolean pendingDesiredLock;

    private final Runnable reconnectRunnable = this::runReconnectAttempt;
    private final Runnable reconnectWatchdog = this::onReconnectWatchdog;
    private final Runnable pollRunnable = this::pollOnce;

    private ScooterRepository(Context context) {
        appContext = context.getApplicationContext();
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ble = new ScooterBleManager(appContext, this);
        rideStats = new RideStatsTracker(appContext);
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
        String key = scooterKey();
        return new Snapshot(state, telemetry.copy(), name,
                ScooterIdentity.displayModel(name, prefs.getString(PREF_SERIAL, null)),
                address(), status, isAutoConnectEnabled(), isPersistentEnabled(),
                isFullChargeAlertEnabled(), rideStats.snapshot(key));
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

    public String scooterKey() {
        String serial = prefs.getString(PREF_SERIAL, null);
        if (serial != null && !serial.trim().isEmpty()) return "serial:" + serial.trim();
        String address = address();
        return address == null ? null : "ble:" + address;
    }

    public RideStatsStore.PeriodSummary periodStats(int period) {
        String key = scooterKey();
        return key == null ? new RideStatsStore.PeriodSummary(0, 0, 0, 0, 0, 0)
                : rideStats.period(key, period);
    }

    public List<RideStatsStore.RideRecord> recentRides(int limit) {
        String key = scooterKey();
        return key == null ? java.util.Collections.emptyList() : rideStats.recentRides(key, limit);
    }

    public boolean endCurrentRide() {
        boolean changed = rideStats.endRideNow(scooterKey());
        if (changed) notifyListeners();
        return changed;
    }

    public boolean continuePreviousRide() {
        boolean changed = rideStats.continuePreviousRide(scooterKey());
        if (changed) notifyListeners();
        return changed;
    }

    public int getRidePauseMinutes() { return rideStats.getPauseMinutes(); }
    public long getRidePauseTimeoutMs() { return rideStats.pauseTimeoutMs(); }

    public void setRidePauseMinutes(int minutes) {
        rideStats.setPauseMinutes(minutes);
        notifyListeners();
    }

    public String exportStatistics() throws JSONException { return rideStats.exportJson(); }

    public void importStatisticsReplace(String json) throws JSONException {
        rideStats.importJsonReplace(json);
        notifyListeners();
    }

    public void setUiActive(boolean active) {
        boolean wasActive = uiActive;
        if (active) uiActiveClients++;
        else if (uiActiveClients > 0) uiActiveClients--;
        uiActive = uiActiveClients > 0;

        if (uiActive && !wasActive) {
            if (isAutoConnectEnabled()) connectIfNeeded();
        } else if (!uiActive && wasActive && !isPersistentEnabled() && !isFullChargeAlertEnabled()) {
            stopPolling();
            cancelReconnect();
            ble.disconnectSilently();
            identityVerified = false;
            clearConnectionDerivedTelemetry();
            telemetry.setConnected(false);
            rideStats.onDisconnected();
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
        identityVerified = false;
        clearConnectionDerivedTelemetry();
        telemetry.setConnected(false);
        rideStats.onDisconnected();
        setState(ScooterConnectionState.DISCONNECTED, "Disconnected");
    }

    public void connectIfNeeded() {
        if (!hasRememberedScooter() || ble.isReady()
                || state == ScooterConnectionState.CONNECTING
                || state == ScooterConnectionState.CONNECTED) return;

        if (state == ScooterConnectionState.RECONNECTING) {
            if (!ble.isConnectionAttemptActive()) {
                main.removeCallbacks(reconnectRunnable);
                runReconnectAttempt();
            } else {
                armReconnectWatchdog();
            }
            return;
        }

        identityVerified = false;
        ble.connect(address(), prefs.getString(PREF_NAME, null), false);
    }

    public void disconnect() {
        cancelReconnect();
        stopPolling();
        identityVerified = false;
        clearConnectionDerivedTelemetry();
        telemetry.setConnected(false);
        rideStats.onDisconnected();
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
        clearConnectionDerivedTelemetry();
        rideStats.onDisconnected();
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
        rideStats.onDisconnected();
        identityVerified = false;
        pendingDesiredLock = null;
        clearConnectionDerivedTelemetry();
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
        cancelReconnectWatchdog();
        String incomingSerial = serialString(serialBytes);
        String rememberedSerial = prefs.getString(PREF_SERIAL, null);
        if (rememberedSerial != null && !rememberedSerial.trim().isEmpty()
                && incomingSerial != null && !rememberedSerial.equals(incomingSerial)) {
            identityVerified = false;
            clearConnectionDerivedTelemetry();
            telemetry.setConnected(false);
            rideStats.onDisconnected();
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
        rideStats.onConnected(scooterKey());
        setState(ScooterConnectionState.READY, "Connected");

        // Keep startup gentle. RSSI is an Android GATT read and is queued behind writes by the
        // transport, so requesting it here does not collide with protocol traffic.
        ble.requestRssi();
        startPolling();
        sendPendingAction();

        if (isPersistentEnabled() || isFullChargeAlertEnabled()) {
            ScooterService.startForConnectedScooter(appContext);
        }
    }

    @Override public void onPacket(byte[] packet) {
        if (identityVerified && G30Protocol.applyTelemetryPacket(packet, telemetry)) {
            int index = ShuNinebotProtocol.index(packet);
            boolean freshCurrent = index == G30Protocol.REG_BMS_CURRENT;
            telemetry.setCharging(chargingDetector.update(
                    telemetry.getBatteryCurrent(), telemetry.getSpeed(),
                    System.currentTimeMillis(), freshCurrent));
            rideStats.onTelemetry(scooterKey(), telemetry);
            notifyListeners();
        }
    }

    @Override public void onRssi(int rssi) {
        if (!identityVerified || !telemetry.isConnected()) return;
        // BLE RSSI is normally negative dBm. Ignore obvious platform/error sentinels.
        if (rssi > 0 || rssi < -127) return;
        telemetry.setRssi(rssi);
        notifyListeners();
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
        cancelReconnectWatchdog();
        identityVerified = false;
        clearConnectionDerivedTelemetry();
        telemetry.setConnected(false);
        rideStats.onDisconnected();
        stopPolling();
        status = reason == null ? "Disconnected" : reason;
        if (shouldStayConnected() && isAutoConnectEnabled() && hasRememberedScooter()) {
            scheduleReconnect();
        } else {
            setState(ScooterConnectionState.DISCONNECTED, status);
        }
    }

    private void clearConnectionDerivedTelemetry() {
        telemetry.setRssi(null);
        telemetry.setCharging(null);
        chargingDetector.reset();
    }

    private boolean shouldStayConnected() {
        return uiActive || isPersistentEnabled() || isFullChargeAlertEnabled() || pendingDesiredLock != null;
    }

    private void scheduleReconnect() {
        if (!shouldStayConnected() || !isAutoConnectEnabled() || !hasRememberedScooter()) return;
        stopPolling();
        cancelReconnectWatchdog();
        main.removeCallbacks(reconnectRunnable);
        int index = Math.min(reconnectAttempt, reconnectDelays.length - 1);
        long delay = reconnectDelays[index];
        if (uiActive) delay = Math.min(delay, 10000L);
        reconnectAttempt++;
        setState(ScooterConnectionState.RECONNECTING, "Reconnecting…");
        main.postDelayed(reconnectRunnable, delay);
    }

    private void runReconnectAttempt() {
        if (!shouldStayConnected() || !isAutoConnectEnabled() || !hasRememberedScooter()) return;
        final String savedAddress = address();
        final String savedName = prefs.getString(PREF_NAME, null);
        identityVerified = false;

        // Most reconnect attempts use Android's direct GATT connection to the remembered MAC.
        // Every third attempt falls back to an 8-second BALANCED scan; scanning is not continuous.
        if (reconnectAttempt % 3 != 0) {
            ble.connect(savedAddress, savedName, true);
            armReconnectWatchdog();
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
                armReconnectWatchdog();
            }
            @Override public void onScanFinished() {
                cancelReconnectWatchdog();
                if (!matched) scheduleReconnect();
            }
            @Override public void onScanError(String message) {
                cancelReconnectWatchdog();
                if (!matched) scheduleReconnect();
            }
        });
        armReconnectWatchdog();
    }

    private void armReconnectWatchdog() {
        main.removeCallbacks(reconnectWatchdog);
        if (shouldStayConnected() && !ble.isReady()) {
            main.postDelayed(reconnectWatchdog, RECONNECT_WATCHDOG_MS);
        }
    }

    private void cancelReconnectWatchdog() { main.removeCallbacks(reconnectWatchdog); }

    private void onReconnectWatchdog() {
        if (!shouldStayConnected() || !isAutoConnectEnabled() || ble.isReady()) return;
        ble.disconnectSilently();
        setState(ScooterConnectionState.RECONNECTING, "Reconnect attempt timed out…");
        scheduleReconnect();
    }

    private void cancelReconnect() {
        main.removeCallbacks(reconnectRunnable);
        cancelReconnectWatchdog();
    }

    private void startPolling() {
        stopPolling();
        pollTick = 0;
        fastPollIndex = 0;
        slowPollIndex = 0;
        startupPollIndex = 0;
        main.postDelayed(pollRunnable, 900L);
    }

    private void stopPolling() { main.removeCallbacks(pollRunnable); }

    private void pollOnce() {
        if (!identityVerified || !ble.isReady()) return;

        byte[] packet;
        long nextDelay;

        // Gentle startup sequence: one request at a time after the authentication settles.
        if (startupPollIndex < 3) {
            switch (startupPollIndex++) {
                case 0: packet = G30Protocol.readBatteryPercent(); break;
                case 1: packet = G30Protocol.readOdometer(); break;
                default: packet = G30Protocol.readLockStatus(); break;
            }
            ble.send(packet);
            main.postDelayed(pollRunnable, 700L);
            return;
        }

        pollTick++;
        // RSSI is sampled infrequently and serialized by NinebotBleClient so it cannot overlap
        // the characteristic write queue. This keeps the feature cheap while still useful.
        if (pollTick % 10 == 0) ble.requestRssi();

        // Session distance is odometer-based, so refresh it predictably every five ticks.
        if (pollTick % 20 == 0) {
            switch (slowPollIndex++ % 3) {
                case 0: packet = G30Protocol.readRemainingRange(); break;
                case 1: packet = G30Protocol.readControllerTemperature(); break;
                default: packet = G30Protocol.readBatteryTemperature(); break;
            }
        } else if (pollTick % 5 == 0) {
            packet = G30Protocol.readOdometer();
        } else {
            // Speed gets half of the fast slots so the notification remains responsive while
            // still keeping the total radio traffic to one request per scheduler tick.
            switch (fastPollIndex++ % 8) {
                case 0:
                case 2:
                case 4:
                case 6: packet = G30Protocol.readSpeed(); break;
                case 1: packet = G30Protocol.readBatteryPercent(); break;
                case 3: packet = G30Protocol.readBatteryCurrent(); break;
                case 5: packet = G30Protocol.readBatteryVoltage(); break;
                default: packet = G30Protocol.readLockStatus(); break;
            }
        }

        ble.send(packet);
        Double speed = telemetry.getSpeed();
        nextDelay = speed != null && speed > 1.0 ? 500L : 1000L;
        main.postDelayed(pollRunnable, nextDelay);
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
