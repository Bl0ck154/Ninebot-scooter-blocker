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

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Small one-shot BLE client for the plain Ninebot UART protocol.
 *
 * It deliberately exposes only the lock operation. No firmware writes and no unlock command are
 * used by the application.
 */
public final class NinebotBleClient {
    public interface Listener {
        void onStatus(String status);
        void onFinished(boolean success, String message);
    }

    private static final UUID UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean(false);

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private boolean commandSent;
    private int phase;

    public NinebotBleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void lock(BluetoothDevice device) {
        status("Connecting to " + safeName(device) + "…");
        main.postDelayed(() -> finish(false,
                "Timeout. The scooter did not accept a plain BLE connection. Newer firmware may require encrypted Ninebot authentication."),
                12000);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    @SuppressLint("MissingPermission")
    public void cancel() {
        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (finished.get()) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected. Discovering Ninebot UART…");
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (commandSent) {
                    // NB_CTL_LOCK is documented to reset the scooter automatically after a lock.
                    finish(true, "Lock command sent. Scooter disconnected/reset after the command.");
                } else {
                    finish(false, "Bluetooth disconnected before the lock command was sent.");
                }
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (finished.get()) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                finish(false, "Could not discover BLE services (GATT " + statusCode + ").");
                return;
            }

            BluetoothGattService service = g.getService(UART_SERVICE);
            if (service == null) {
                finish(false, "Ninebot UART service was not found. This dashboard may use encrypted/newer BLE authentication.");
                return;
            }

            rx = service.getCharacteristic(UART_RX);
            BluetoothGattCharacteristic tx = service.getCharacteristic(UART_TX);
            if (rx == null) {
                finish(false, "Ninebot UART write characteristic was not found.");
                return;
            }

            if (tx != null) {
                BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
                if (cccd != null && g.setCharacteristicNotification(tx, true)) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (g.writeDescriptor(cccd)) {
                        status("UART ready. Enabling response notifications…");
                        return;
                    }
                }
            }
            sendReplyRequest(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            if (finished.get()) return;
            sendReplyRequest(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            handleNotification(characteristic.getValue());
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (finished.get()) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS && phase == 1) {
                status("Reply-mode write failed; trying compatibility mode…");
                sendCompatibilitySequence(g);
            }
        }
    };

    private void handleNotification(byte[] data) {
        if (NinebotProtocol.isPositiveWriteAck(data, NinebotProtocol.REG_LOCK)) {
            finish(true, "Locked — Ninebot controller acknowledged NB_CTL_LOCK.");
        }
    }

    @SuppressLint("MissingPermission")
    private void sendReplyRequest(BluetoothGatt g) {
        if (finished.get() || rx == null || phase != 0) return;
        phase = 1;
        status("Sending LOCK (0x70 = 1)…");
        boolean queued = write(g, NinebotProtocol.lockPacket(NinebotProtocol.SOURCE_PC, true), true);
        commandSent = queued;
        if (!queued) {
            sendCompatibilitySequence(g);
            return;
        }

        main.postDelayed(() -> {
            if (!finished.get()) sendCompatibilitySequence(g);
        }, 1000);
    }

    @SuppressLint("MissingPermission")
    private void sendCompatibilitySequence(BluetoothGatt g) {
        if (finished.get() || rx == null || phase >= 2) return;
        phase = 2;
        status("No ACK yet. Sending plain compatibility LOCK…");
        boolean firstQueued = write(g, NinebotProtocol.lockPacket(NinebotProtocol.SOURCE_PC, false), false);
        commandSent = commandSent || firstQueued;

        main.postDelayed(() -> {
            if (finished.get() || rx == null) return;
            status("Trying phone source ID compatibility…");
            boolean secondQueued = write(g, NinebotProtocol.lockPacket(NinebotProtocol.SOURCE_PHONE, false), false);
            commandSent = commandSent || secondQueued;
            main.postDelayed(() -> {
                if (!finished.get()) {
                    finish(false, "Lock packet was sent but the controller did not acknowledge it. Check whether the wheel is electronically braked. If it is not, this firmware likely requires encrypted Ninebot authentication.");
                }
            }, 900);
        }, 450);
    }

    @SuppressLint("MissingPermission")
    private boolean write(BluetoothGatt g, byte[] packet, boolean withResponse) {
        if (rx == null) return false;
        rx.setWriteType(withResponse
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        rx.setValue(packet);
        return g.writeCharacteristic(rx);
    }

    private void status(String text) {
        main.post(() -> listener.onStatus(text));
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null || name.isBlank() ? device.getAddress() : name;
        } catch (SecurityException e) {
            return device.getAddress();
        }
    }

    @SuppressLint("MissingPermission")
    private void finish(boolean success, String message) {
        if (!finished.compareAndSet(false, true)) return;
        main.post(() -> listener.onFinished(success, message));
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }
}
