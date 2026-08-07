package com.bl0ck154.ninebotblocker;

import java.util.Arrays;

/** Minimal packet builder matching ScooterHacking Utility 2.7 classic Ninebot commands. */
public final class ShuNinebotProtocol {
    private ShuNinebotProtocol() {}

    public static final int SOURCE_PHONE = 0x3E;
    public static final int TARGET_ESC = 0x20;
    public static final int CMD_WRITE_REGISTER = 0x32;
    public static final int REG_LOCK = 0x70;

    /** Internal SHU command before the classic Ninebot 5A A5 wrapper. */
    public static byte[] lockCommand() {
        return new byte[] {
                (byte) SOURCE_PHONE,
                (byte) TARGET_ESC,
                (byte) CMD_WRITE_REGISTER,
                (byte) REG_LOCK,
                0x01
        };
    }

    /**
     * Exact classic Ninebot frame emitted by SHU 2.7 for Lock:
     * 5A A5 01 3E 20 32 70 01 FD FE
     */
    public static byte[] plainLockFrame() {
        return wrapPlain(lockCommand());
    }

    public static byte[] wrapPlain(byte[] command) {
        if (command == null || command.length < 4) {
            throw new IllegalArgumentException("Ninebot command must contain source, target, command and register");
        }

        int dataLength = command.length - 4;
        byte[] out = new byte[3 + command.length + 2];
        out[0] = 0x5A;
        out[1] = (byte) 0xA5;
        out[2] = (byte) dataLength;
        System.arraycopy(command, 0, out, 3, command.length);

        int sum = 0;
        for (int i = 2; i < 3 + command.length; i++) {
            sum = (sum + (out[i] & 0xFF)) & 0xFFFF;
        }
        int checksum = (~sum) & 0xFFFF;
        out[out.length - 2] = (byte) (checksum & 0xFF);
        out[out.length - 1] = (byte) ((checksum >>> 8) & 0xFF);
        return out;
    }

    public static boolean equalsPlainLockFrame(byte[] frame) {
        return Arrays.equals(plainLockFrame(), frame);
    }

    public static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(String.format("%02X", b & 0xFF));
        }
        return sb.toString();
    }
}
