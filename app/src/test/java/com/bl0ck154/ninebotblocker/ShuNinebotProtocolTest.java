package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public final class ShuNinebotProtocolTest {
    @Test
    public void buildsExactShu27InitPacket() {
        assertArrayEquals(
                hex("5AA5003E215B00"),
                ShuNinebotProtocol.initPacket());
    }

    @Test
    public void buildsExactShu27FixedKeyPingPacket() {
        assertArrayEquals(
                hex("5AA5103E215C004AEEBD73E2161C112D065A49CC6E8BB7"),
                ShuNinebotProtocol.pingPacket());
    }

    @Test
    public void buildsExactShu27PairPacket() {
        byte[] serial = hex("3031323334353637383941424344");
        assertArrayEquals(
                hex("5AA50E3E215D003031323334353637383941424344"),
                ShuNinebotProtocol.pairPacket(serial));
    }

    @Test
    public void buildsExactShu27CryptoLockPlaintext() {
        assertArrayEquals(
                hex("5AA5013E20027001"),
                ShuNinebotProtocol.lockPacket());
    }

    @Test
    public void buildsExactShu27CryptoUnlockPlaintext() {
        assertArrayEquals(
                hex("5AA5013E20027101"),
                ShuNinebotProtocol.unlockPacket());
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
