package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NinebotCryptoTest {
    @Test
    public void encryptsInitialPacketLikeNinebotCrypto() {
        NinebotCrypto crypto = new NinebotCrypto("NBScooter2020");
        byte[] encrypted = crypto.encrypt(NinebotProtocol.initPacket());
        assertArrayEquals(hex("5AA500F565B968000046FF0000"), encrypted);
        assertEquals(1, crypto.counter());
    }

    @Test
    public void pingUsesNextCounterAfterInit() {
        NinebotCrypto crypto = new NinebotCrypto("NBScooter2020");
        crypto.encrypt(NinebotProtocol.initPacket());
        crypto.setBleData(hex("000102030405060708090A0B0C0D0E0F"));

        byte[] appKey = hex("101112131415161718191A1B1C1D1E1F");
        byte[] encryptedPing = crypto.encrypt(NinebotProtocol.pingPacket(appKey));

        assertEquals(2, crypto.counter());
        assertArrayEquals(
                hex("5AA51073AE1E8FDC35483AC359641C16B0CD872F7C5D581303B2690002"),
                encryptedPing);
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
