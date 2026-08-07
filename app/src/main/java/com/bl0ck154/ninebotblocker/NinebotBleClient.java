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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/** One-scooter authenticated Ninebot BLE client. */
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

    private static final String PREFS = "ninebot_quick_lock";
    private static final String APP_KEY_PREFIX = "app_key_";

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private NinebotCrypto crypto;
    private byte[] appKey;
    private byte[] serial;
    private String address;
    private String deviceName;
    private boolean cancelled;
    private boolean ready;
    private boolean writeInFlight;
    private boolean pairLoop;
    private boolean lockInFlight;
    private boolean pendingLock;

    private final Runnable connectionTimeout = () -> {
        if (!cancelled && !ready) {
            failConnection("Authentication timed out. Keep the scooter on and close other scooter apps.");
        }
    };

    private final Runnable pairRetry = new Runnable() {
        @Override public void run() {
            if (cancelled || ready || !pairLoop || serial == null) return;
            sendEncrypted(NinebotProtocol.pairPacket(serial));
            main.postDelayed(this, 1100);
        }
    };

    private final Runnable lockTimeout = () -> {
        if (!cancelled && lockInFlight) {
            lockInFlight = false;
            listener.onLockResult(false,
                    "Authenticated, but the controller did not acknowledge LOCK. Try once more and send this exact message if it repeats.");
        }
    };

    public NinebotBleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() {
        return ready && gatt != null;
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, String preferredName) {
        cancelInternal(false);
        cancelled = false;
        ready = false;
        pendingLock = false;
        lockInFlight = false;
        pairLoop = false;
        writeQueue.clear();
        receiveBuffer.reset();

        address = device.getAddress();
        deviceName = preferredName;
        if (deviceName == null || deviceName.isBlank()) deviceName = safeName(device);
        if (deviceName == null || deviceName.isBlank()) deviceName = "NBScooter2020";

        crypto = new NinebotCrypto(deviceName);
        appKey = loadOrCreateAppKey(address);

        status("Connecting directly to " + deviceName + "…");
        main.removeCallbacks(connectionTimeout);
        main.postDelayed(connectionTimeout, 18000);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    /** If not ready yet, remember the click and lock immediately after authentication. */
    public void lockWhenReady() {
        if (cancelled) return;
        if (!ready) {
            pendingLock = true;
            status("LOCK queued — authenticating scooter first…");
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
        pairLoop = false;
        lockInFlight = false;
        pendingLock = false;
        writeInFlight = false;
        writeQueue.clear();
        receiveBuffer.reset();
        main.removeCallbacks(connectionTimeout);
        main.removeCallbacks(pairRetry);
        main.removeCallbacks(lockTimeout);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        rx = null;
        if (notify) listener.onDisconnected("Disconnected");
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (cancelled) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected. Opening Ninebot UART…");
                if (!g.discoverServices()) failConnection("Could not start BLE service discovery.");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                if (!cancelled) failConnection("Scooter disconnected (GATT " + statusCode + ").");
            }
        }

        @Override
        @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (cancelled) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Could not discover BLE services (GATT " + statusCode + ").");
                return;
            }

            BluetoothGattService service = g.getService(UART_SERVICE);
            if (service == null) {
                failConnection("Ninebot UART service was not found.");
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
            status("UART ready. Enabling encrypted responses…");
            if (!g.writeDescriptor(cccd)) {
                failConnection("Could not subscribe to Ninebot responses.");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            if (cancelled) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Notification setup failed (GATT " + statusCode + ").");
                return;
            }
            status("Authenticating: INIT…");
            sendEncrypted(NinebotProtocol.initPacket());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null && value.length > 0) handleEncryptedFragment(value);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (cancelled) return;
            writeInFlight = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Bluetooth write failed (GATT " + statusCode + ").");
                return;
            }
            writeNextChunk();
        }
    };

    private void handleEncryptedFragment(byte[] fragment) {
        try {
            if (fragment.length >= 2 && (fragment[0] & 0xFF) == 0x5A && (fragment[1] & 0xFF) == 0xA5) {
                receiveBuffer.reset();
            }
            receiveBuffer.write(fragment, 0, fragment.length);
            byte[] accumulated = receiveBuffer.toByteArray();
            if (accumulated.length < 3) return;

            int expectedEncryptedLength = (accumulated[2] & 0xFF) + 13;
            if (accumulated.length < expectedEncryptedLength) return;
            if (accumulated.length > expectedEncryptedLength) {
                accumulated = Arrays.copyOf(accumulated, expectedEncryptedLength);
            }
            receiveBuffer.reset();

            byte[] plain = crypto.decrypt(accumulated);
            if (!NinebotProtocol.isPacket(plain)) {
                status("Ignored malformed Ninebot response.");
                return;
            }
            handlePacket(plain);
        } catch (Exception e) {
            failConnection("Could not decrypt Ninebot response: " + e.getMessage());
        }
    }

    private void handlePacket(byte[] packet) {
        int command = NinebotProtocol.command(packet);
        int index = NinebotProtocol.index(packet);
        byte[] payload = NinebotProtocol.payload(packet);

        if (command == NinebotProtocol.CMD_INIT && index == 1) {
            if (payload.length < 17) {
                failConnection("INIT response was too short.");
                return;
            }
            crypto.setBleData(Arrays.copyOfRange(payload, 0, 16));
            serial = Arrays.copyOfRange(payload, 16, payload.length);
            status("Authenticating: PING…");
            sendEncrypted(NinebotProtocol.pingPacket(appKey));
            return;
        }

        if (command == NinebotProtocol.CMD_PING) {
            if (index == 0) {
                status("First pairing: press the scooter POWER button once…");
                pairLoop = true;
                main.removeCallbacks(pairRetry);
                main.post(pairRetry);
            } else if (index == 1) {
                pairLoop = false;
                main.removeCallbacks(pairRetry);
                crypto.setAppData(appKey);
                status("Pair key accepted. Finishing authentication…");
                sendEncrypted(NinebotProtocol.pairPacket(serial));
            }
            return;
        }

        if (command == NinebotProtocol.CMD_PAIR && index == 1) {
            pairLoop = false;
            main.removeCallbacks(pairRetry);
            markReady();
            return;
        }

        if (NinebotProtocol.isPositiveWriteAck(packet, NinebotProtocol.REG_LOCK)) {
            main.removeCallbacks(lockTimeout);
            lockInFlight = false;
            listener.onLockResult(true, "LOCKED — controller acknowledged NB_CTL_LOCK.");
        }
    }

    private void markReady() {
        if (ready) return;
        ready = true;
        main.removeCallbacks(connectionTimeout);
        status("Connected + authenticated. Ready to lock.");
        listener.onReady();
        if (pendingLock) {
            pendingLock = false;
            sendLock();
        }
    }

    private void sendLock() {
        if (!ready || lockInFlight) return;
        lockInFlight = true;
        status("Sending authenticated LOCK (0x70 = 1)…");
        sendEncrypted(NinebotProtocol.lockPacket());
        main.removeCallbacks(lockTimeout);
        main.postDelayed(lockTimeout, 2500);
    }

    private void sendEncrypted(byte[] plainPacket) {
        if (cancelled || crypto == null || rx == null) return;
        byte[] encrypted = crypto.encrypt(plainPacket);
        for (int offset = 0; offset < encrypted.length; offset += 20) {
            int n = Math.min(20, encrypted.length - offset);
            writeQueue.add(Arrays.copyOfRange(encrypted, offset, offset + n));
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
            failConnection("Android rejected a BLE write.");
        }
    }

    private byte[] loadOrCreateAppKey(String deviceAddress) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String keyName = APP_KEY_PREFIX + deviceAddress.replace(":", "");
        String stored = prefs.getString(keyName, null);
        if (stored != null && stored.length() == 32) {
            try { return fromHex(stored); } catch (Exception ignored) {}
        }
        byte[] key = new byte[16];
        new SecureRandom().nextBytes(key);
        prefs.edit().putString(keyName, NinebotProtocol.hex(key).replace(" ", "")).apply();
        return key;
    }

    private static byte[] fromHex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private void failConnection(String message) {
        if (cancelled) return;
        boolean wasReady = ready;
        cancelInternal(false);
        if (wasReady) listener.onDisconnected(message);
        else listener.onDisconnected(message);
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
