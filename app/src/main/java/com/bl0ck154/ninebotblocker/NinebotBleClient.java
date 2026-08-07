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

/** One-scooter Ninebot BLE client with legacy-first protocol auto-detection. */
public final class NinebotBleClient {
    public interface Listener {
        void onStatus(String status);
        void onReady();
        void onDisconnected(String reason);
        void onLockResult(boolean success, String message);
    }

    private enum ProtocolMode { DETECTING, LEGACY_55AA, MODERN_5AA5 }

    private static final UUID UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID UART_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final String PREFS = "ninebot_quick_lock";
    private static final String APP_KEY_PREFIX = "app_key_";

    private final Context context;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private final ByteArrayOutputStream modernReceiveBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream legacyReceiveBuffer = new ByteArrayOutputStream();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private NinebotCrypto crypto;
    private byte[] appKey;
    private byte[] serial;
    private String address;
    private String deviceName;
    private ProtocolMode mode = ProtocolMode.DETECTING;
    private boolean cancelled;
    private boolean ready;
    private boolean writeInFlight;
    private boolean pairLoop;
    private boolean lockInFlight;
    private boolean pendingLock;

    private final Runnable connectionTimeout = () -> {
        if (!cancelled && !ready) {
            failConnection("Connection/protocol detection timed out. Keep the scooter on and close other scooter apps.");
        }
    };

    private final Runnable protocolProbeTimeout = () -> {
        if (!cancelled && !ready && mode == ProtocolMode.DETECTING) {
            startModernAuthentication();
        }
    };

    private final Runnable pairRetry = new Runnable() {
        @Override public void run() {
            if (cancelled || ready || !pairLoop || serial == null || mode != ProtocolMode.MODERN_5AA5) return;
            sendEncrypted(NinebotProtocol.pairPacket(serial));
            main.postDelayed(this, 1100);
        }
    };

    private final Runnable lockTimeout = () -> {
        if (!cancelled && lockInFlight) {
            lockInFlight = false;
            if (mode == ProtocolMode.LEGACY_55AA) {
                listener.onLockResult(false,
                        "Legacy LOCK was sent, but the lock-state read did not confirm it. Check whether the wheel is electronically braked.");
            } else {
                listener.onLockResult(false,
                        "Authenticated, but the controller did not acknowledge LOCK. Try once more and send this exact message if it repeats.");
            }
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
        mode = ProtocolMode.DETECTING;
        writeQueue.clear();
        modernReceiveBuffer.reset();
        legacyReceiveBuffer.reset();

        address = device.getAddress();
        deviceName = preferredName;
        if (deviceName == null || deviceName.isBlank()) deviceName = safeName(device);
        if (deviceName == null || deviceName.isBlank()) deviceName = "NBScooter2020";

        crypto = new NinebotCrypto(deviceName);
        appKey = loadOrCreateAppKey(address);

        status("Connecting directly to " + deviceName + "…");
        main.removeCallbacks(connectionTimeout);
        main.postDelayed(connectionTimeout, 20000);
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    }

    /** If not ready yet, remember the click and lock immediately after protocol detection/auth. */
    public void lockWhenReady() {
        if (cancelled) return;
        if (!ready) {
            pendingLock = true;
            status("LOCK queued — connecting to scooter first…");
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
        modernReceiveBuffer.reset();
        legacyReceiveBuffer.reset();
        main.removeCallbacks(connectionTimeout);
        main.removeCallbacks(protocolProbeTimeout);
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
            if (cancelled || g != gatt) return;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("Connected. Opening Ninebot UART…");
                if (!g.discoverServices()) failConnection("Could not start BLE service discovery.");
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
            status("UART ready. Enabling responses…");
            if (!g.writeDescriptor(cccd)) {
                failConnection("Could not subscribe to Ninebot responses.");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            if (cancelled || g != gatt) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Notification setup failed (GATT " + statusCode + ").");
                return;
            }

            mode = ProtocolMode.DETECTING;
            status("Detecting Ninebot protocol: trying legacy 55AA first…");
            sendRaw(LegacyNinebotProtocol.readFirmwarePacket());
            main.postDelayed(() -> {
                if (!cancelled && !ready && mode == ProtocolMode.DETECTING) {
                    sendRaw(LegacyNinebotProtocol.readLockStatePacket());
                }
            }, 150);
            main.removeCallbacks(protocolProbeTimeout);
            main.postDelayed(protocolProbeTimeout, 1100);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (cancelled || g != gatt) return;
            byte[] value = characteristic.getValue();
            if (value == null || value.length == 0) return;

            boolean legacyHeader = value.length >= 2
                    && (value[0] & 0xFF) == 0x55 && (value[1] & 0xFF) == 0xAA;
            boolean modernHeader = value.length >= 2
                    && (value[0] & 0xFF) == 0x5A && (value[1] & 0xFF) == 0xA5;

            if (legacyHeader || mode == ProtocolMode.LEGACY_55AA || legacyReceiveBuffer.size() > 0) {
                handleLegacyFragment(value);
            } else if (modernHeader || mode == ProtocolMode.MODERN_5AA5 || modernReceiveBuffer.size() > 0) {
                handleEncryptedFragment(value);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (cancelled || g != gatt) return;
            writeInFlight = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                failConnection("Bluetooth write failed (GATT " + statusCode + ").");
                return;
            }
            writeNextChunk();
        }
    };

    private void handleLegacyFragment(byte[] fragment) {
        try {
            if (fragment.length >= 2 && (fragment[0] & 0xFF) == 0x55 && (fragment[1] & 0xFF) == 0xAA) {
                legacyReceiveBuffer.reset();
            }
            legacyReceiveBuffer.write(fragment, 0, fragment.length);
            byte[] accumulated = legacyReceiveBuffer.toByteArray();
            if (accumulated.length < 3) return;

            int expectedLength = (accumulated[2] & 0xFF) + 6;
            if (accumulated.length < expectedLength) return;
            if (accumulated.length > expectedLength) accumulated = Arrays.copyOf(accumulated, expectedLength);
            legacyReceiveBuffer.reset();

            if (!LegacyNinebotProtocol.isPacket(accumulated)) {
                status("Ignored malformed legacy Ninebot response.");
                return;
            }

            if (mode == ProtocolMode.DETECTING) {
                mode = ProtocolMode.LEGACY_55AA;
                main.removeCallbacks(protocolProbeTimeout);
                markReady("Legacy 55AA protocol detected. Ready to lock.");
            }

            if (mode != ProtocolMode.LEGACY_55AA) return;
            int command = LegacyNinebotProtocol.command(accumulated);
            if (command == LegacyNinebotProtocol.REG_LOCK_STATE && lockInFlight) {
                main.removeCallbacks(lockTimeout);
                lockInFlight = false;
                if (LegacyNinebotProtocol.lockState(accumulated)) {
                    listener.onLockResult(true, "LOCKED — legacy lock-state bit is ON.");
                } else {
                    listener.onLockResult(false, "Controller replied, but lock-state is OFF. The legacy LOCK command was not accepted.");
                }
            }
        } catch (Exception e) {
            status("Legacy response parse error: " + e.getMessage());
        }
    }

    private void startModernAuthentication() {
        if (cancelled || ready || mode != ProtocolMode.DETECTING) return;
        mode = ProtocolMode.MODERN_5AA5;
        modernReceiveBuffer.reset();
        status("Legacy 55AA did not answer. Trying encrypted Ninebot authentication: INIT…");
        sendEncrypted(NinebotProtocol.initPacket());
    }

    private void handleEncryptedFragment(byte[] fragment) {
        try {
            if (fragment.length >= 2 && (fragment[0] & 0xFF) == 0x5A && (fragment[1] & 0xFF) == 0xA5) {
                modernReceiveBuffer.reset();
            }
            modernReceiveBuffer.write(fragment, 0, fragment.length);
            byte[] accumulated = modernReceiveBuffer.toByteArray();
            if (accumulated.length < 3) return;

            int expectedEncryptedLength = (accumulated[2] & 0xFF) + 13;
            if (accumulated.length < expectedEncryptedLength) return;
            if (accumulated.length > expectedEncryptedLength) {
                accumulated = Arrays.copyOf(accumulated, expectedEncryptedLength);
            }
            modernReceiveBuffer.reset();

            byte[] plain = crypto.decrypt(accumulated);
            if (!NinebotProtocol.isPacket(plain)) {
                status("Ignored malformed encrypted Ninebot response.");
                return;
            }
            handleModernPacket(plain);
        } catch (Exception e) {
            failConnection("Could not decrypt Ninebot response: " + e.getMessage());
        }
    }

    private void handleModernPacket(byte[] packet) {
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
            status("Encrypted protocol: PING…");
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
            markReady("Encrypted protocol authenticated. Ready to lock.");
            return;
        }

        if (NinebotProtocol.isPositiveWriteAck(packet, NinebotProtocol.REG_LOCK)) {
            main.removeCallbacks(lockTimeout);
            lockInFlight = false;
            listener.onLockResult(true, "LOCKED — controller acknowledged encrypted NB_CTL_LOCK.");
        }
    }

    private void markReady(String message) {
        if (ready) return;
        ready = true;
        main.removeCallbacks(connectionTimeout);
        main.removeCallbacks(protocolProbeTimeout);
        status(message);
        listener.onReady();
        if (pendingLock) {
            pendingLock = false;
            sendLock();
        }
    }

    private void sendLock() {
        if (!ready || lockInFlight) return;
        if (mode == ProtocolMode.LEGACY_55AA) {
            sendLegacyLock();
        } else if (mode == ProtocolMode.MODERN_5AA5) {
            sendModernLock();
        }
    }

    private void sendLegacyLock() {
        lockInFlight = true;
        status("Sending legacy LOCK: 55AA / register 0x70 = 1…");
        sendRaw(LegacyNinebotProtocol.lockPacket());
        main.postDelayed(() -> {
            if (!cancelled && lockInFlight && mode == ProtocolMode.LEGACY_55AA) {
                status("Legacy LOCK sent. Reading lock state…");
                sendRaw(LegacyNinebotProtocol.readLockStatePacket());
            }
        }, 350);
        main.removeCallbacks(lockTimeout);
        main.postDelayed(lockTimeout, 2500);
    }

    private void sendModernLock() {
        lockInFlight = true;
        status("Sending authenticated LOCK (0x70 = 1)…");
        sendEncrypted(NinebotProtocol.lockPacket());
        main.removeCallbacks(lockTimeout);
        main.postDelayed(lockTimeout, 2500);
    }

    private void sendEncrypted(byte[] plainPacket) {
        if (cancelled || crypto == null || rx == null) return;
        sendRaw(crypto.encrypt(plainPacket));
    }

    private void sendRaw(byte[] packet) {
        if (cancelled || packet == null || rx == null) return;
        for (int offset = 0; offset < packet.length; offset += 20) {
            int n = Math.min(20, packet.length - offset);
            writeQueue.add(Arrays.copyOfRange(packet, offset, offset + n));
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
        cancelInternal(false);
        listener.onDisconnected(message);
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
