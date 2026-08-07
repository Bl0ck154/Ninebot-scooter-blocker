package com.bl0ck154.ninebotblocker;

import java.util.Arrays;
import java.util.Locale;

/** Packet shapes taken directly from ScooterHacking Utility 2.7. */
public final class ShuNinebotProtocol {
    private ShuNinebotProtocol() {}

    public static final int SOURCE_PHONE = 0x3E;
    public static final int TARGET_ESC = 0x20;
    public static final int TARGET_BLE = 0x21;

    public static final int CMD_WRITE_REGISTER = 0x02;
    public static final int CMD_INIT = 0x5B;
    public static final int CMD_PING = 0x5C;
    public static final int CMD_PAIR = 0x5D;
    public static final int REG_LOCK = 0x70;

    /** Exact 16-byte application key embedded in SHU 2.7's w2() pairing command. */
    public static final byte[] SHU_APP_KEY = hex("4AEEBD73E2161C112D065A49CC6E8BB7");

    public static byte[] initPacket() {
        return wrapCryptoPlain(new byte[]{
                (byte) SOURCE_PHONE, (byte) TARGET_BLE, (byte) CMD_INIT, 0x00
        });
    }

    public static byte[] pingPacket() {
        byte[] command = new byte[20];
        command[0] = (byte) SOURCE_PHONE;
        command[1] = (byte) TARGET_BLE;
        command[2] = (byte) CMD_PING;
        command[3] = 0x00;
        System.arraycopy(SHU_APP_KEY, 0, command, 4, 16);
        return wrapCryptoPlain(command);
    }

    public static byte[] pairPacket(byte[] serial) {
        if (serial == null || serial.length < 14) {
            throw new IllegalArgumentException("SHU pairing requires the 14-byte scooter serial");
        }
        byte[] command = new byte[18];
        command[0] = (byte) SOURCE_PHONE;
        command[1] = (byte) TARGET_BLE;
        command[2] = (byte) CMD_PAIR;
        command[3] = 0x00;
        System.arraycopy(serial, 0, command, 4, 14);
        return wrapCryptoPlain(command);
    }

    /** Exact SHU WriteRegister command: 3E 20 02 70 01. */
    public static byte[] lockPacket() {
        return wrapCryptoPlain(new byte[]{
                (byte) SOURCE_PHONE,
                (byte) TARGET_ESC,
                (byte) CMD_WRITE_REGISTER,
                (byte) REG_LOCK,
                0x01
        });
    }

    /**
     * SHU NinebotCrypto wrapper before encryption: 5A A5, data length, then the
     * logical command bytes. data length is command.length - 4.
     */
    public static byte[] wrapCryptoPlain(byte[] command) {
        if (command == null || command.length < 4) {
            throw new IllegalArgumentException("Ninebot command too short");
        }
        byte[] out = new byte[command.length + 3];
        out[0] = 0x5A;
        out[1] = (byte) 0xA5;
        out[2] = (byte) (command.length - 4);
        System.arraycopy(command, 0, out, 3, command.length);
        return out;
    }

    public static boolean isPacket(byte[] packet) {
        if (packet == null || packet.length < 7) return false;
        if ((packet[0] & 0xFF) != 0x5A || (packet[1] & 0xFF) != 0xA5) return false;
        int dataLength = packet[2] & 0xFF;
        return packet.length >= 7 + dataLength;
    }

    public static int command(byte[] packet) {
        return isPacket(packet) ? packet[5] & 0xFF : -1;
    }

    public static int index(byte[] packet) {
        return isPacket(packet) ? packet[6] & 0xFF : -1;
    }

    public static byte[] payload(byte[] packet) {
        if (!isPacket(packet)) return new byte[0];
        int len = packet[2] & 0xFF;
        return Arrays.copyOfRange(packet, 7, 7 + len);
    }

    public static int encryptedPacketLengthFromHeader(byte[] data) {
        if (data == null || data.length < 3) return -1;
        return (data[2] & 0xFF) + 13;
    }

    public static String hex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
