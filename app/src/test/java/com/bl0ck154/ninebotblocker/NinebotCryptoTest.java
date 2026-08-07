package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class NinebotCryptoTest {
    @Test
    public void encryptsInitialPacketLikeNinebotCrypto() {
        NinebotCrypto crypto = new NinebotCrypto("NBScooter2020");
        byte[] encrypted = crypto.encrypt(NinebotProtocol.initPacket());
        assertArrayEquals(hex("5AA500F565B968000046FF0000"), encrypted);
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
