package com.bl0ck154.ninebotblocker;

import java.util.Arrays;

/** Minimal plain Ninebot serial/BLE protocol encoder. */
public final class NinebotProtocol {
    private NinebotProtocol() {}

    public static final int SOURCE_PC = 0x3D;
    public static final int SOURCE_PHONE = 0x3E;
    public static final int TARGET_ESC = 0x20;
    public static final int CMD_READ = 0x01;
    public static final int CMD_WRITE_REPLY = 0x02;
    public static final int CMD_WRITE_NO_REPLY = 0x03;
    public static final int CMD_READ_ACK = 0x04;
    public static final int CMD_WRITE_ACK = 0x05;
    public static final int REG_LOCK = 0x70;
    public static final int REG_LOCK_STATE = 0x1D;

    public static byte[] lockPacket(int sourceId, boolean requestReply) {
        return writeS16(sourceId, TARGET_ESC, requestReply ? CMD_WRITE_REPLY : CMD_WRITE_NO_REPLY,
                REG_LOCK, 1);
    }

    public static byte[] readLockStatePacket(int sourceId) {
        return build(sourceId, TARGET_ESC, CMD_READ, REG_LOCK_STATE, new byte[]{0x02});
    }

    public static boolean isPositiveWriteAck(byte[] data, int index) {
        if (data == null || data.length < 8 || (data[0] & 0xFF) != 0x5A || (data[1] & 0xFF) != 0xA5) {
            return false;
        }
        int command = data[5] & 0xFF;
        int responseIndex = data[6] & 0xFF;
        return command == CMD_WRITE_ACK && responseIndex == index && (data[7] & 0xFF) == 1;
    }

    private static byte[] writeS16(int sourceId, int targetId, int command, int index, int value) {
        return build(sourceId, targetId, command, index,
                new byte[]{(byte) (value & 0xFF), (byte) ((value >>> 8) & 0xFF)});
    }

    private static byte[] build(int sourceId, int targetId, int command, int index, byte[] payload) {
        byte[] packet = new byte[2 + 5 + payload.length + 2];
        packet[0] = 0x5A;
        packet[1] = (byte) 0xA5;
        packet[2] = (byte) payload.length;
        packet[3] = (byte) sourceId;
        packet[4] = (byte) targetId;
        packet[5] = (byte) command;
        packet[6] = (byte) index;
        System.arraycopy(payload, 0, packet, 7, payload.length);

        int sum = 0;
        for (int i = 2; i < 7 + payload.length; i++) {
            sum = (sum + (packet[i] & 0xFF)) & 0xFFFF;
        }
        int checksum = (~sum) & 0xFFFF;
        packet[7 + payload.length] = (byte) (checksum & 0xFF);
        packet[8 + payload.length] = (byte) ((checksum >>> 8) & 0xFF);
        return packet;
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
