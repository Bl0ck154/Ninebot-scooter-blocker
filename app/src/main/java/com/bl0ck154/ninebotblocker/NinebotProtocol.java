package com.bl0ck154.ninebotblocker;

import java.util.Arrays;

/** Packet encoder/decoder for the modern Ninebot 5A A5 BLE protocol. */
public final class NinebotProtocol {
    private NinebotProtocol() {}

    public static final int SOURCE_PC = 0x3D;
    public static final int SOURCE_PHONE = 0x3E;
    public static final int TARGET_ESC = 0x20;
    public static final int TARGET_BLE = 0x21;

    public static final int CMD_READ = 0x01;
    public static final int CMD_WRITE_REPLY = 0x02;
    public static final int CMD_WRITE_NO_REPLY = 0x03;
    public static final int CMD_READ_ACK = 0x04;
    public static final int CMD_WRITE_ACK = 0x05;
    public static final int CMD_INIT = 0x5B;
    public static final int CMD_PING = 0x5C;
    public static final int CMD_PAIR = 0x5D;

    public static final int REG_LOCK = 0x70;
    public static final int REG_LOCK_STATE = 0x1D;

    public static byte[] initPacket() {
        return build(SOURCE_PC, TARGET_BLE, CMD_INIT, 0, new byte[0]);
    }

    public static byte[] pingPacket(byte[] appKey) {
        if (appKey == null || appKey.length != 16) {
            throw new IllegalArgumentException("Ninebot app key must be exactly 16 bytes");
        }
        return build(SOURCE_PC, TARGET_BLE, CMD_PING, 0, appKey);
    }

    public static byte[] pairPacket(byte[] serial) {
        if (serial == null || serial.length == 0) {
            throw new IllegalArgumentException("Ninebot serial is required for pairing");
        }
        return build(SOURCE_PC, TARGET_BLE, CMD_PAIR, 0, serial);
    }

    /** NB_CTL_LOCK = 0x70, value = little-endian signed/unsigned 16-bit 1. */
    public static byte[] lockPacket() {
        return build(SOURCE_PC, TARGET_ESC, CMD_WRITE_REPLY, REG_LOCK,
                new byte[]{0x01, 0x00});
    }

    public static byte[] readLockStatePacket() {
        return build(SOURCE_PC, TARGET_ESC, CMD_READ, REG_LOCK_STATE, new byte[]{0x02});
    }

    public static byte[] build(int sourceId, int targetId, int command, int index, byte[] data) {
        byte[] payload = data == null ? new byte[0] : data;
        byte[] packet = new byte[7 + payload.length];
        packet[0] = 0x5A;
        packet[1] = (byte) 0xA5;
        packet[2] = (byte) payload.length;
        packet[3] = (byte) sourceId;
        packet[4] = (byte) targetId;
        packet[5] = (byte) command;
        packet[6] = (byte) index;
        System.arraycopy(payload, 0, packet, 7, payload.length);
        return packet;
    }

    public static boolean isPacket(byte[] data) {
        if (data == null || data.length < 7) return false;
        if ((data[0] & 0xFF) != 0x5A || (data[1] & 0xFF) != 0xA5) return false;
        int segmentLength = data[2] & 0xFF;
        return data.length >= 7 + segmentLength;
    }

    public static int command(byte[] data) {
        return isPacket(data) ? data[5] & 0xFF : -1;
    }

    public static int index(byte[] data) {
        return isPacket(data) ? data[6] & 0xFF : -1;
    }

    public static byte[] payload(byte[] data) {
        if (!isPacket(data)) return new byte[0];
        int len = data[2] & 0xFF;
        return Arrays.copyOfRange(data, 7, 7 + len);
    }

    public static boolean isPositiveWriteAck(byte[] data, int register) {
        if (!isPacket(data)) return false;
        if (command(data) != CMD_WRITE_ACK || index(data) != register) return false;
        byte[] payload = payload(data);
        return payload.length == 0 || (payload[0] & 0xFF) == 1;
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
