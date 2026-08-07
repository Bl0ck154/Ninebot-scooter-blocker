package com.bl0ck154.ninebotblocker;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/** Single BLE coordinator. Activities/services never own BluetoothGatt or ScanCallback objects. */
@SuppressLint("MissingPermission")
public final class ScooterBleManager {
    public interface Listener {
        void onConnectionState(ScooterConnectionState state, String message);
        void onReady(byte[] serial);
        void onPacket(byte[] packet);
        void onActionResult(boolean success, Boolean locked, String message);
        void onDisconnected(String reason);
    }
    public interface ScanListener {
        void onDeviceFound(String address, String name, int rssi);
        void onScanFinished();
        void onScanError(String message);
    }

    private final Context context;
    private final BluetoothAdapter adapter;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private NinebotBleClient client;
    private boolean scanning;
    private boolean reconnecting;

    public ScooterBleManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isBluetoothEnabled() { return adapter != null && adapter.isEnabled(); }
    public boolean isReady() { return client != null && client.isReady(); }
    public boolean isScanning() { return scanning; }

    public void connect(String address, String preferredName, boolean reconnect) {
        stopScan();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onConnectionState(ScooterConnectionState.ERROR, "Turn Bluetooth on");
            listener.onDisconnected("Bluetooth is off.");
            return;
        }
        if (address == null || address.trim().isEmpty()) {
            listener.onConnectionState(ScooterConnectionState.ERROR, "No scooter selected");
            return;
        }
        disconnectSilently();
        reconnecting = reconnect;
        listener.onConnectionState(reconnect ? ScooterConnectionState.RECONNECTING : ScooterConnectionState.CONNECTING,
                reconnect ? "Reconnecting…" : "Connecting…");
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            client = new NinebotBleClient(context, new NinebotBleClient.Listener() {
                @Override public void onStatus(String status) {
                    if (status != null && status.contains("POWER")) {
                        listener.onConnectionState(reconnecting ? ScooterConnectionState.RECONNECTING : ScooterConnectionState.CONNECTING, status);
                    }
                }
                @Override public void onTransportConnected() { listener.onConnectionState(ScooterConnectionState.CONNECTED, "Bluetooth connected"); }
                @Override public void onReady() {
                    listener.onConnectionState(ScooterConnectionState.READY, "Connected");
                    listener.onReady(client == null ? null : client.getSerial());
                }
                @Override public void onPacket(byte[] packet) { listener.onPacket(packet); }
                @Override public void onActionResult(boolean success, Boolean locked, String message) { listener.onActionResult(success, locked, message); }
                @Override public void onDisconnected(String reason) { listener.onDisconnected(reason); }
            });
            client.connect(device, preferredName);
        } catch (IllegalArgumentException e) {
            listener.onConnectionState(ScooterConnectionState.ERROR, "Saved scooter address is invalid");
            listener.onDisconnected("Saved scooter address is invalid.");
        } catch (SecurityException e) {
            listener.onConnectionState(ScooterConnectionState.ERROR, "Bluetooth permission is required");
            listener.onDisconnected("Bluetooth permission is required.");
        }
    }

    public boolean send(byte[] protocolPacket) {
        NinebotBleClient current = client;
        return current != null && current.sendProtocolPacket(protocolPacket);
    }

    public boolean setLocked(boolean locked) {
        NinebotBleClient current = client;
        if (current == null || !current.isReady()) return false;
        current.setLockedWhenReady(locked);
        return true;
    }

    public void startScan(boolean lowPower, long timeoutMs, ScanListener callback) {
        stopScan();
        reconnecting = lowPower;
        if (adapter == null || !adapter.isEnabled()) { callback.onScanError("Turn Bluetooth on"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { callback.onScanError("Bluetooth scanner is unavailable"); return; }
        scanning = true;
        listener.onConnectionState(lowPower ? ScooterConnectionState.RECONNECTING : ScooterConnectionState.SCANNING,
                lowPower ? "Looking for saved scooter…" : "Searching…");
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                String name = advertisedName(result);
                if (!looksLikeNinebot(name)) return;
                try { callback.onDeviceFound(result.getDevice().getAddress(), name, result.getRssi()); }
                catch (SecurityException ignored) {}
            }
            @Override public void onScanFailed(int errorCode) {
                stopScanInternal();
                callback.onScanError("BLE scan failed: " + errorCode);
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(lowPower ? ScanSettings.SCAN_MODE_LOW_POWER : ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(null, settings, scanCallback);
            main.postDelayed(() -> {
                if (!scanning) return;
                stopScanInternal();
                callback.onScanFinished();
            }, Math.max(1000, timeoutMs));
        } catch (SecurityException e) {
            stopScanInternal();
            callback.onScanError("Bluetooth permission is required");
        }
    }

    public void stopScan() { stopScanInternal(); }

    private void stopScanInternal() {
        if (scanning && scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
        scanCallback = null;
        scanner = null;
    }

    public void disconnect() {
        stopScan();
        reconnecting = false;
        NinebotBleClient old = client;
        client = null;
        if (old != null) old.cancel();
        listener.onConnectionState(ScooterConnectionState.DISCONNECTED, "Disconnected");
    }

    public void disconnectSilently() {
        stopScan();
        NinebotBleClient old = client;
        client = null;
        if (old != null) old.closeSilently();
    }

    private static String advertisedName(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        if (name == null || name.trim().isEmpty()) {
            try { name = result.getDevice().getName(); } catch (SecurityException ignored) {}
        }
        return name == null ? "" : name.trim();
    }

    static boolean looksLikeNinebot(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase(Locale.US);
        return upper.startsWith("NBSCOOTER") || upper.contains("NINEBOT") || upper.contains("SEGWAY");
    }
}
