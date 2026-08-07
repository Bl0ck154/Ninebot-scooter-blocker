package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class ShuNinebotProtocolTest {
    @Test
    public void buildsExactShu27ClassicLockCommand() {
        assertArrayEquals(
                hex("3E20327001"),
                ShuNinebotProtocol.lockCommand());
    }

    @Test
    public void buildsExactShu27ClassicLockFrame() {
        assertArrayEquals(
                hex("5AA5013E20327001FDFE"),
                ShuNinebotProtocol.plainLockFrame());
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
