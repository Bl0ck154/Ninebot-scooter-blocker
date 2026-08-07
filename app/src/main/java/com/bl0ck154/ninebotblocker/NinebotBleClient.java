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
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/**
 * Minimal one-scooter BLE client matching ScooterHacking Utility 2.7's
 * classic Ninebot path. There is deliberately no INIT/PING/PAIR handshake.
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
            failConnection("Connection timed out. Keep the scooter on and close other scooter apps.");
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

        status("Connecting directly to " + name + "…");
        main.removeCallbacks(connectionTimeout);
        main.postDelayed(connectionTimeout, 10000);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    /** Queue the user's click if Android is still establishing the BLE connection. */
    public void lockWhenReady() {
        if (cancelled) return;
        if (!isReady()) {
            pendingLock = true;
            status("LOCK queued — connecting to scooter…");
            return;
        }
        sendLock();
    }

    @SuppressLint("MissingPermission")
    public void cancel() {
        cancelInternal(true);
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
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected. Opening Ninebot UART…");
                if (!g.discoverServices()) {
                    failConnection("Could not start BLE service discovery.");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                failConnection("Scooter disconnected (GATT " + statusCode + ").");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (cancelled || g != gatt) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Could not discover BLE services (GATT " + statusCode + ").");
                return;
            }

            BluetoothGattService service = g.getService(UART_SERVICE);
            if (service == null) {
                failConnection("Ninebot Nordic UART service was not found.");
                return;
            }
            rx = service.getCharacteristic(UART_RX);
            BluetoothGattCharacteristic tx = service.getCharacteristic(UART_TX);
            if (rx == null || tx == null) {
                failConnection("Ninebot UART characteristics were not found.");
                return;
            }

            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
            if (cccd == null || !g.setCharacteristicNotification(tx, true)) {
                failConnection("Could not enable Ninebot notifications.");
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            status("UART found. Finishing connection…");
            if (!g.writeDescriptor(cccd)) {
                failConnection("Could not subscribe to Ninebot notifications.");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            if (cancelled || g != gatt) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Notification setup failed (GATT " + statusCode + ").");
                return;
            }

            // This is what SHU 2.7 does for its classic Ninebot mode: once the
            // normal Nordic UART is connected, commands can be sent immediately.
            ready = true;
            main.removeCallbacks(connectionTimeout);
            status("Connected — SHU classic Ninebot mode. Ready.");
            main.post(listener::onReady);
            if (pendingLock) {
                pendingLock = false;
                sendLock();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            // Lock-only v0.5 does not need telemetry. Notifications are enabled to
            // mirror SHU's normal UART setup and to leave room for later verification.
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (cancelled || g != gatt) return;
            writeInFlight = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                lockInFlight = false;
                main.post(() -> listener.onLockResult(false,
                        "Bluetooth accepted the connection but the SHU LOCK write failed (GATT " + statusCode + ")."));
                return;
            }

            if (!writeQueue.isEmpty()) {
                writeNextChunk();
                return;
            }

            if (lockInFlight) {
                lockInFlight = false;
                main.post(() -> listener.onLockResult(true,
                        "SHU LOCK command sent: 5A A5 01 3E 20 32 70 01 FD FE"));
            }
        }
    };

    private void sendLock() {
        if (!isReady() || lockInFlight) return;
        lockInFlight = true;
        byte[] frame = ShuNinebotProtocol.plainLockFrame();
        status("Sending exact SHU 2.7 LOCK: " + ShuNinebotProtocol.hex(frame));
        enqueueRaw(frame);
    }

    private void enqueueRaw(byte[] frame) {
        if (cancelled || rx == null || frame == null) return;
        // Keep Nordic-UART chunks at 20 bytes for compatibility with the old G30 BLE.
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
        if (!gatt.writeCharacteristic(rx)) {
            writeInFlight = false;
            lockInFlight = false;
            main.post(() -> listener.onLockResult(false, "Android rejected the BLE write."));
        }
    }

    private void failConnection(String message) {
        if (cancelled) return;
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
}
