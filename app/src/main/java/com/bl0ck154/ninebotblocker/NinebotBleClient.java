package com.bl0ck154.ninebotblocker;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * Minimal one-scooter BLE client matching ScooterHacking Utility 2.7's
 * classic Ninebot path. v0.5.1 adds verbose diagnostics but deliberately
 * does not invent any new protocol handshakes.
 */
public final class NinebotBleClient {
    public interface Listener {
        void onStatus(String status);
        void onReady();
        void onDisconnected(String reason);
        void onLockResult(boolean success, String message);
    }

    private static final UUID UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private boolean cancelled;
    private boolean ready;
    private boolean pendingLock;
    private boolean writeInFlight;
    private boolean lockInFlight;

    private final Runnable connectionTimeout = () -> {
        if (!cancelled && !ready) {
            failConnection("Connection timed out before UART became ready.");
        }
    };

    public NinebotBleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() {
        return ready && gatt != null && rx != null;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, String preferredName) {
        cancelInternal(false);
        cancelled = false;
        ready = false;
        pendingLock = false;
        lockInFlight = false;
        writeInFlight = false;
        writeQueue.clear();

        String name = preferredName;
        if (name == null || name.isBlank()) name = safeName(device);
        if (name == null || name.isBlank()) name = "Ninebot";

        status("GATT connect start: " + name + " / " + device.getAddress());
        main.removeCallbacks(connectionTimeout);
        main.postDelayed(connectionTimeout, 12000);
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            failConnection("connectGatt exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Queue the user's click if Android is still establishing the BLE connection. */
    public void lockWhenReady() {
        if (cancelled) return;
        if (!isReady()) {
            pendingLock = true;
            status("LOCK queued; waiting for UART ready");
            return;
        }
        sendLock();
    }

    @SuppressLint("MissingPermission")
    public void cancel() {
        cancelInternal(true);
    }

    @SuppressLint("MissingPermission")
    public void closeSilently() {
        cancelInternal(false);
    }

    @SuppressLint("MissingPermission")
    private void cancelInternal(boolean notify) {
        cancelled = true;
        ready = false;
        pendingLock = false;
        writeInFlight = false;
        lockInFlight = false;
        writeQueue.clear();
        main.removeCallbacks(connectionTimeout);

        BluetoothGatt old = gatt;
        gatt = null;
        rx = null;
        if (old != null) {
            try { old.disconnect(); } catch (Exception ignored) {}
            try { old.close(); } catch (Exception ignored) {}
        }
        if (notify) main.post(() -> listener.onDisconnected("Disconnected"));
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (cancelled || g != gatt) return;
            status("GATT state: status=" + statusCode + " newState=" + stateName(newState));
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    boolean priority = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    status("requestConnectionPriority(HIGH)=" + priority);
                } catch (Exception e) {
                    status("requestConnectionPriority exception: " + e.getMessage());
                }
                status("discoverServices() start");
                if (!g.discoverServices()) {
                    failConnection("Could not start BLE service discovery.");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                failConnection("Scooter disconnected (GATT status " + statusCode + ").");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (cancelled || g != gatt) return;
            status("Services discovered: status=" + statusCode + " count=" + g.getServices().size());
            for (BluetoothGattService service : g.getServices()) {
                status("SERVICE " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    status("  CHAR " + c.getUuid() + " props=0x" + Integer.toHexString(c.getProperties()));
                }
            }

            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Could not discover BLE services (GATT " + statusCode + ").");
                return;
            }

            BluetoothGattService service = g.getService(UART_SERVICE);
            if (service == null) {
                failConnection("Expected Nordic UART service " + UART_SERVICE + " was NOT found. See service list above.");
                return;
            }
            rx = service.getCharacteristic(UART_RX);
            BluetoothGattCharacteristic tx = service.getCharacteristic(UART_TX);
            if (rx == null || tx == null) {
                failConnection("Nordic UART service exists, but RX/TX characteristic is missing.");
                return;
            }
            status("Nordic UART found: RX(write)=" + UART_RX + " TX(notify)=" + UART_TX);

            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
            if (cccd == null) {
                failConnection("UART TX has no CCCD descriptor.");
                return;
            }
            boolean localNotify = g.setCharacteristicNotification(tx, true);
            status("setCharacteristicNotification(TX,true)=" + localNotify);
            if (!localNotify) {
                failConnection("Could not enable local Ninebot notifications.");
                return;
            }

            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            status("Writing TX CCCD 01 00");
            if (!g.writeDescriptor(cccd)) {
                failConnection("Android rejected the TX CCCD descriptor write.");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            if (cancelled || g != gatt) return;
            status("Descriptor write callback: " + descriptor.getUuid() + " status=" + statusCode);
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Notification setup failed (GATT " + statusCode + ").");
                return;
            }

            ready = true;
            main.removeCallbacks(connectionTimeout);
            status("UART READY; no INIT/PING/PAIR is being sent");
            main.post(listener::onReady);
            if (pendingLock) {
                pendingLock = false;
                sendLock();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (cancelled || g != gatt) return;
            byte[] value = characteristic.getValue();
            handleRx(characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (cancelled || g != gatt) return;
            handleRx(characteristic, value);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (cancelled || g != gatt) return;
            writeInFlight = false;
            status("TX callback: status=" + statusCode + " uuid=" + characteristic.getUuid());
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                lockInFlight = false;
                main.post(() -> listener.onLockResult(false,
                        "BLE characteristic write failed (GATT " + statusCode + "). See diagnostic log."));
                return;
            }

            if (!writeQueue.isEmpty()) {
                writeNextChunk();
                return;
            }

            if (lockInFlight) {
                lockInFlight = false;
                main.post(() -> listener.onLockResult(true,
                        "Android confirmed SHU LOCK bytes were written. Check whether scooter actually locked; RX is in the log."));
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int statusCode) {
            if (cancelled || g != gatt) return;
            status("MTU changed: mtu=" + mtu + " status=" + statusCode);
        }
    };

    private void handleRx(BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value == null) value = new byte[0];
        status("RX " + characteristic.getUuid() + " [" + value.length + "] " + hex(value));
    }

    private void sendLock() {
        if (!isReady() || lockInFlight) return;
        lockInFlight = true;
        byte[] frame = ShuNinebotProtocol.plainLockFrame();
        status("LOCK TX exact SHU frame [" + frame.length + "]: " + hex(frame));
        enqueueRaw(frame);
    }

    private void enqueueRaw(byte[] frame) {
        if (cancelled || rx == null || frame == null) return;
        for (int offset = 0; offset < frame.length; offset += 20) {
            int n = Math.min(20, frame.length - offset);
            writeQueue.add(Arrays.copyOfRange(frame, offset, offset + n));
        }
        writeNextChunk();
    }

    @SuppressLint("MissingPermission")
    private void writeNextChunk() {
        if (cancelled || writeInFlight || gatt == null || rx == null) return;
        byte[] chunk = writeQueue.poll();
        if (chunk == null) return;

        writeInFlight = true;
        rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        rx.setValue(chunk);
        status("TX writeType=DEFAULT [" + chunk.length + "] " + hex(chunk));
        boolean accepted;
        try {
            accepted = gatt.writeCharacteristic(rx);
        } catch (Exception e) {
            accepted = false;
            status("writeCharacteristic exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        status("writeCharacteristic() accepted=" + accepted);
        if (!accepted) {
            writeInFlight = false;
            lockInFlight = false;
            main.post(() -> listener.onLockResult(false, "Android rejected the BLE write. See diagnostic log."));
        }
    }

    private void failConnection(String message) {
        if (cancelled) return;
        status("FAIL: " + message);
        cancelInternal(false);
        main.post(() -> listener.onDisconnected(message));
    }

    private void status(String text) {
        main.post(() -> listener.onStatus(text));
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "" : name;
        } catch (SecurityException e) {
            return "";
        }
    }

    private String stateName(int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) return "CONNECTED";
        if (state == BluetoothProfile.STATE_CONNECTING) return "CONNECTING";
        if (state == BluetoothProfile.STATE_DISCONNECTED) return "DISCONNECTED";
        if (state == BluetoothProfile.STATE_DISCONNECTING) return "DISCONNECTING";
        return String.valueOf(state);
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "<empty>";
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return out.toString();
    }
}
