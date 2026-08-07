package com.bl0ck154.ninebotblocker;

import java.util.Arrays;

/** Legacy Ninebot/M365 55 AA protocol used by classic G30 dashboards. */
public final class LegacyNinebotProtocol {
    private LegacyNinebotProtocol() {}

    public static final int DIRECTION_SCOOTER = 0x20;
    public static final int DIRECTION_SCOOTER_REPLY = 0x23;
    public static final int READ = 0x01;
    public static final int WRITE_NO_REPLY = 0x03;

    public static final int REG_FIRMWARE = 0x1A;
    public static final int REG_LOCK_STATE = 0x1D;
    public static final int REG_LOCK = 0x70;

    public static byte[] readFirmwarePacket() {
        return readPacket(REG_FIRMWARE, 2);
    }

    public static byte[] readLockStatePacket() {
        return readPacket(REG_LOCK_STATE, 2);
    }

    public static byte[] lockPacket() {
        return writePacket(REG_LOCK, new byte[]{0x01, 0x00});
    }

    public static byte[] readPacket(int register, int expectedBytes) {
        if (expectedBytes < 2 || expectedBytes > 0x38) {
            throw new IllegalArgumentException("Invalid Ninebot read length");
        }
        return build(DIRECTION_SCOOTER, READ, register, new byte[]{(byte) expectedBytes});
    }

    public static byte[] writePacket(int register, byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 0x38) {
            throw new IllegalArgumentException("Invalid Ninebot write payload");
        }
        return build(DIRECTION_SCOOTER, WRITE_NO_REPLY, register, payload);
    }

    public static byte[] build(int direction, int rw, int command, byte[] payload) {
        byte[] data = payload == null ? new byte[0] : payload;
        // Legacy length includes payload plus the two checksum bytes.
        int length = data.length + 2;
        byte[] out = new byte[data.length + 8];
        out[0] = 0x55;
        out[1] = (byte) 0xAA;
        out[2] = (byte) length;
        out[3] = (byte) direction;
        out[4] = (byte) rw;
        out[5] = (byte) command;
        System.arraycopy(data, 0, out, 6, data.length);

        int sum = length + direction + rw + command;
        for (byte b : data) sum += b & 0xFF;
        int checksum = (~sum) & 0xFFFF;
        out[out.length - 2] = (byte) (checksum & 0xFF);
        out[out.length - 1] = (byte) ((checksum >>> 8) & 0xFF);
        return out;
    }

    public static boolean isPacket(byte[] packet) {
        if (packet == null || packet.length < 8) return false;
        if ((packet[0] & 0xFF) != 0x55 || (packet[1] & 0xFF) != 0xAA) return false;
        int length = packet[2] & 0xFF;
        if (packet.length != length + 6 || length < 2) return false;

        int sum = 0;
        for (int i = 2; i < packet.length - 2; i++) sum += packet[i] & 0xFF;
        int expected = (~sum) & 0xFFFF;
        int actual = (packet[packet.length - 2] & 0xFF)
                | ((packet[packet.length - 1] & 0xFF) << 8);
        return expected == actual;
    }

    public static int direction(byte[] packet) {
        return isPacket(packet) ? packet[3] & 0xFF : -1;
    }

    public static int rw(byte[] packet) {
        return isPacket(packet) ? packet[4] & 0xFF : -1;
    }

    public static int command(byte[] packet) {
        return isPacket(packet) ? packet[5] & 0xFF : -1;
    }

    public static byte[] payload(byte[] packet) {
        if (!isPacket(packet)) return new byte[0];
        int payloadLength = (packet[2] & 0xFF) - 2;
        return Arrays.copyOfRange(packet, 6, 6 + payloadLength);
    }

    public static boolean lockState(byte[] packet) {
        if (!isPacket(packet) || command(packet) != REG_LOCK_STATE) return false;
        byte[] data = payload(packet);
        if (data.length < 2) return false;
        int stateWord = (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        return (stateWord & (1 << 1)) != 0;
    }
}
