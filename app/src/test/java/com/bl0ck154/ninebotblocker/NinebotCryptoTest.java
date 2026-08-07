package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class NinebotCryptoTest {
    @Test
    public void encryptsFirstShuInitAndAdvancesLocalCounter() {
        NinebotCrypto crypto = new NinebotCrypto("NBScooter2020");
        byte[] encrypted = crypto.encrypt(ShuNinebotProtocol.initPacket());

        assertArrayEquals(
                hex("5AA500F665B968000045FF0000"),
                encrypted);
        assertEquals(1, crypto.counter());
    }

    @Test
    public void initResponseRekeysAndNextPingUsesCounterTwo() {
        NinebotCrypto app = new NinebotCrypto("NBScooter2020");
        app.encrypt(ShuNinebotProtocol.initPacket());
        assertEquals(1, app.counter());

        byte[] bleKey = hex("000102030405060708090A0B0C0D0E0F");
        byte[] serial = hex("3031323334353637383941424344");
        byte[] plainResponse = concat(
                hex("5AA51E213E5B01"),
                bleKey,
                serial);

        NinebotCrypto scooter = new NinebotCrypto("NBScooter2020");
        byte[] encryptedResponse = scooter.encrypt(plainResponse);
        byte[] decoded = app.decrypt(encryptedResponse);

        assertArrayEquals(plainResponse, decoded);
        assertEquals(1, app.counter());

        app.encrypt(ShuNinebotProtocol.pingPacket());
        assertEquals(2, app.counter());
    }

    @Test
    public void fixedShuAppKeyIsExact() {
        assertArrayEquals(
                hex("4AEEBD73E2161C112D065A49CC6E8BB7"),
                ShuNinebotProtocol.SHU_APP_KEY);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
