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

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;

/** Known-good SHU 2.7 G30 transport; higher layers add reconnect/telemetry around it. */
public final class NinebotBleClient {
    public interface Listener {
        void onStatus(String status);
        void onReady();
        void onDisconnected(String reason);
        void onActionResult(boolean success, Boolean locked, String message);
        default void onTransportConnected() {}
        default void onPacket(byte[] packet) {}
        default void onRssi(int rssi) {}
    }

    public static final UUID UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID UART_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID UART_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context context;
    private Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx;
    private NinebotCrypto crypto;
    private byte[] serial;
    private boolean cancelled;
    private boolean ready;
    private boolean writeInFlight;
    private boolean rssiReadInFlight;
    private boolean rssiRequested;
    private boolean initAccepted;
    private boolean pingAccepted;
    private boolean pairAccepted;
    private boolean firstPairTriggerSent;
    private boolean pairingPromptShown;
    private Boolean pendingDesiredLock;
    private Boolean requestedLocked;

    private final Runnable connectionTimeout = () -> {
        if (!cancelled && !ready) failConnection("Connection timed out. Keep the scooter on and try again.");
    };

    private final Runnable initRetry = new Runnable() {
        @Override public void run() {
            if (cancelled || ready || initAccepted || rx == null) return;
            status("Authenticating…");
            sendEncrypted(ShuNinebotProtocol.initPacket());
            main.postDelayed(this, 900);
        }
    };

    private final Runnable pingRetry = new Runnable() {
        @Override public void run() {
            if (cancelled || ready || pingAccepted || !initAccepted || rx == null) return;
            status(pairingPromptShown ? "Press the scooter POWER button once to pair." : "Authenticating…");
            sendEncrypted(ShuNinebotProtocol.pingPacket());
            if (!firstPairTriggerSent && serial != null) {
                firstPairTriggerSent = true;
                main.postDelayed(() -> {
                    if (!cancelled && !ready && !pingAccepted && serial != null) {
                        sendEncrypted(ShuNinebotProtocol.pairPacket(serial));
                    }
                }, 500);
                main.postDelayed(this, 1000);
            } else main.postDelayed(this, 500);
        }
    };

    private final Runnable pairRetry = new Runnable() {
        @Override public void run() {
            if (cancelled || ready || pairAccepted || !pingAccepted || serial == null || rx == null) return;
            status("Authenticating…");
            sendEncrypted(ShuNinebotProtocol.pairPacket(serial));
            main.postDelayed(this, 500);
        }
    };

    private final Runnable actionComplete = () -> {
        if (!cancelled && requestedLocked != null) {
            Boolean completed = requestedLocked;
            requestedLocked = null;
            listener.onActionResult(true, completed, completed ? "Scooter locked." : "Scooter unlocked.");
        }
    };

    public NinebotBleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public boolean isReady() { return ready && gatt != null && rx != null; }
    public byte[] getSerial() { return serial == null ? null : Arrays.copyOf(serial, serial.length); }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device, String preferredName) {
        closeInternal(false);
        cancelled = false;
        ready = false;
        writeInFlight = false;
        rssiReadInFlight = false;
        rssiRequested = false;
        initAccepted = false;
        pingAccepted = false;
        pairAccepted = false;
        firstPairTriggerSent = false;
        pairingPromptShown = false;
        pendingDesiredLock = null;
        requestedLocked = null;
        serial = null;
        writeQueue.clear();
        receiveBuffer.reset();

        String name = preferredName;
        if (name == null || name.trim().isEmpty()) name = safeName(device);
        if (name == null || name.trim().isEmpty()) name = "NBScooter2020";
        crypto = new NinebotCrypto(name);

        status("Connecting…");
        main.removeCallbacks(connectionTimeout);
        main.postDelayed(connectionTimeout, 25000);
        try { gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE); }
        catch (Exception e) { failConnection("Bluetooth connection failed."); }
    }

    public void setLockedWhenReady(boolean locked) {
        if (cancelled) return;
        pendingDesiredLock = locked;
        if (!isReady()) { status("Connecting…"); return; }
        sendPendingAction();
    }

    public boolean sendProtocolPacket(byte[] plainPacket) {
        if (!isReady() || plainPacket == null) return false;
        sendEncrypted(plainPacket);
        return true;
    }

    /** Queue a connected-device RSSI read without overlapping an active GATT write. */
    public void requestRssi() {
        if (!isReady()) return;
        rssiRequested = true;
        maybeStartRssiRead();
    }

    @SuppressLint("MissingPermission") public void cancel() { closeInternal(true); }
    @SuppressLint("MissingPermission") public void closeSilently() { closeInternal(false); }

    @SuppressLint("MissingPermission")
    private void closeInternal(boolean notify) {
        cancelled = true;
        ready = false;
        writeInFlight = false;
        rssiReadInFlight = false;
        rssiRequested = false;
        pendingDesiredLock = null;
        requestedLocked = null;
        writeQueue.clear();
        receiveBuffer.reset();
        main.removeCallbacks(connectionTimeout);
        main.removeCallbacks(initRetry);
        main.removeCallbacks(pingRetry);
        main.removeCallbacks(pairRetry);
        main.removeCallbacks(actionComplete);
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
        @Override @SuppressLint("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            if (cancelled || g != gatt) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                failConnection("Bluetooth GATT error " + statusCode + ".");
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post(listener::onTransportConnected);
                status("Connecting…");
                // Keep Android's normal BLE connection parameters. The app only exchanges tiny
                // control/telemetry packets, so forcing priority renegotiation is unnecessary.
                if (!g.discoverServices()) failConnection("Could not open scooter Bluetooth services.");
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) failConnection("Scooter disconnected.");
        }

        @Override @SuppressLint("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (cancelled || g != gatt) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { failConnection("Could not open scooter Bluetooth services."); return; }
            BluetoothGattService service = g.getService(UART_SERVICE);
            if (service == null) { failConnection("Ninebot Bluetooth service not found."); return; }
            rx = service.getCharacteristic(UART_RX);
            BluetoothGattCharacteristic tx = service.getCharacteristic(UART_TX);
            if (rx == null || tx == null) { failConnection("Ninebot Bluetooth channel is unavailable."); return; }
            BluetoothGattDescriptor cccd = tx.getDescriptor(CCCD);
            if (cccd == null || !g.setCharacteristicNotification(tx, true)) { failConnection("Could not enable scooter responses."); return; }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(cccd)) failConnection("Could not enable scooter responses.");
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int statusCode) {
            if (cancelled || g != gatt) return;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { failConnection("Bluetooth setup failed."); return; }
            main.removeCallbacks(initRetry);
            main.post(initRetry);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            if (cancelled || g != gatt) return;
            byte[] value = characteristic.getValue();
            if (value != null && value.length > 0) handleEncryptedFragment(value);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic, byte[] value) {
            if (cancelled || g != gatt) return;
            if (value != null && value.length > 0) handleEncryptedFragment(value);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic characteristic, int statusCode) {
            if (cancelled || g != gatt) return;
            writeInFlight = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) { failConnection("Bluetooth write failed."); return; }
            writeNextChunk();
        }

        @Override public void onReadRemoteRssi(BluetoothGatt g, int rssi, int statusCode) {
            if (cancelled || g != gatt) return;
            rssiReadInFlight = false;
            if (statusCode == BluetoothGatt.GATT_SUCCESS) main.post(() -> listener.onRssi(rssi));
            writeNextChunk();
        }
    };

    private void handleEncryptedFragment(byte[] fragment) {
        try {
            if (fragment.length >= 2 && (fragment[0] & 0xFF) == 0x5A && (fragment[1] & 0xFF) == 0xA5 && receiveBuffer.size() > 0) receiveBuffer.reset();
            receiveBuffer.write(fragment, 0, fragment.length);
            while (true) {
                byte[] accumulated = receiveBuffer.toByteArray();
                if (accumulated.length < 3) return;
                int expected = ShuNinebotProtocol.encryptedPacketLengthFromHeader(accumulated);
                if (expected < 13 || accumulated.length < expected) return;
                byte[] frame = Arrays.copyOfRange(accumulated, 0, expected);
                byte[] remainder = Arrays.copyOfRange(accumulated, expected, accumulated.length);
                receiveBuffer.reset();
                if (remainder.length > 0) receiveBuffer.write(remainder, 0, remainder.length);
                byte[] plain = crypto.decrypt(frame);
                if (ShuNinebotProtocol.isPacket(plain)) handlePacket(plain);
                if (remainder.length == 0) return;
            }
        } catch (Exception e) {
            receiveBuffer.reset();
            if (!ready) failConnection("Scooter response could not be decoded.");
        }
    }

    private void handlePacket(byte[] packet) {
        int command = ShuNinebotProtocol.command(packet);
        int index = ShuNinebotProtocol.index(packet);
        byte[] payload = ShuNinebotProtocol.payload(packet);
        if (command == ShuNinebotProtocol.CMD_INIT && payload.length == 30) {
            serial = Arrays.copyOfRange(payload, 16, 30);
            if (!initAccepted) {
                initAccepted = true;
                main.removeCallbacks(initRetry);
                main.removeCallbacks(pingRetry);
                main.post(pingRetry);
            }
            return;
        }
        if (command == ShuNinebotProtocol.CMD_PING) {
            if (index == 1) {
                if (!pingAccepted) {
                    pingAccepted = true;
                    main.removeCallbacks(pingRetry);
                    main.removeCallbacks(pairRetry);
                    main.post(pairRetry);
                }
            } else if (index == 0) {
                pairingPromptShown = true;
                status("Press the scooter POWER button once to pair.");
            }
            return;
        }
        if (command == ShuNinebotProtocol.CMD_PAIR && index == 1) {
            pairAccepted = true;
            main.removeCallbacks(pairRetry);
            markReady();
            return;
        }
        if (requestedLocked != null) {
            int expectedReg = requestedLocked ? ShuNinebotProtocol.REG_LOCK : ShuNinebotProtocol.REG_UNLOCK;
            if (index == expectedReg && (command == 0x02 || command == 0x05 || command == 0x32)) {
                Boolean completed = requestedLocked;
                requestedLocked = null;
                main.removeCallbacks(actionComplete);
                listener.onActionResult(true, completed, completed ? "Scooter locked." : "Scooter unlocked.");
            }
        }
        byte[] safeCopy = Arrays.copyOf(packet, packet.length);
        main.post(() -> listener.onPacket(safeCopy));
    }

    private void markReady() {
        if (ready) return;
        ready = true;
        main.removeCallbacks(connectionTimeout);
        main.removeCallbacks(initRetry);
        main.removeCallbacks(pingRetry);
        main.removeCallbacks(pairRetry);
        status("Connected");
        listener.onReady();
        sendPendingAction();
    }

    private void sendPendingAction() {
        if (!isReady() || pendingDesiredLock == null || requestedLocked != null) return;
        boolean desired = pendingDesiredLock;
        pendingDesiredLock = null;
        requestedLocked = desired;
        status(desired ? "Locking…" : "Unlocking…");
        sendEncrypted(desired ? ShuNinebotProtocol.lockPacket() : ShuNinebotProtocol.unlockPacket());
        main.removeCallbacks(actionComplete);
        main.postDelayed(actionComplete, 700);
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
        if (cancelled || writeInFlight || rssiReadInFlight || gatt == null || rx == null) return;
        byte[] chunk = writeQueue.poll();
        if (chunk == null) {
            maybeStartRssiRead();
            return;
        }
        writeInFlight = true;
        rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        rx.setValue(chunk);
        boolean accepted;
        try { accepted = gatt.writeCharacteristic(rx); } catch (Exception e) { accepted = false; }
        if (!accepted) { writeInFlight = false; failConnection("Android rejected the Bluetooth write."); }
    }

    @SuppressLint("MissingPermission")
    private void maybeStartRssiRead() {
        if (cancelled || !ready || gatt == null || !rssiRequested || writeInFlight
                || rssiReadInFlight || !writeQueue.isEmpty()) return;
        rssiRequested = false;
        rssiReadInFlight = true;
        boolean accepted;
        try { accepted = gatt.readRemoteRssi(); } catch (Exception e) { accepted = false; }
        if (!accepted) rssiReadInFlight = false;
    }

    private void failConnection(String message) {
        if (cancelled) return;
        closeInternal(false);
        main.post(() -> listener.onDisconnected(message));
    }

    private void status(String text) { main.post(() -> listener.onStatus(text)); }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice device) {
        try {
            String name = device.getName();
            return name == null ? "" : name;
        } catch (SecurityException e) { return ""; }
    }
}
