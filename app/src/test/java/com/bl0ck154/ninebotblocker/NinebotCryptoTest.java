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
        // miauth does not advance the counter merely because INIT was transmitted.
        assertEquals(0, crypto.counter());
    }

    @Test
    public void pingUsesFirstCipherWhileSynchronizedCounterIsZero() {
        NinebotCrypto crypto = new NinebotCrypto("NBScooter2020");
        crypto.encrypt(NinebotProtocol.initPacket());
        crypto.setBleData(hex("000102030405060708090A0B0C0D0E0F"));

        byte[] appKey = hex("101112131415161718191A1B1C1D1E1F");
        byte[] encryptedPing = crypto.encrypt(NinebotProtocol.pingPacket(appKey));

        assertEquals(0, crypto.counter());
        assertArrayEquals(
                hex("5AA510610F02B92FEB678793EFE2B88ABBADFE403340A60000CDFD0000"),
                encryptedPing);
    }

    @Test
    public void firstMessageCrcTreatsPayloadBytesAsUnsigned() {
        NinebotCrypto crypto = new NinebotCrypto("NBScooter2020");
        crypto.setBleData(hex("000102030405060708090A0B0C0D0E0F"));

        byte[] appKey = hex("808182838485868788898A8B8C8D8E8F");
        byte[] encryptedPing = crypto.encrypt(NinebotProtocol.pingPacket(appKey));

        // Last six bytes are 00 00 + CRC16 + 00 00. This vector catches the
        // Java-signed-byte bug that INIT could not expose because its bytes are < 0x80.
        assertArrayEquals(hex("0000CDF60000"),
                java.util.Arrays.copyOfRange(encryptedPing, encryptedPing.length - 6, encryptedPing.length));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
