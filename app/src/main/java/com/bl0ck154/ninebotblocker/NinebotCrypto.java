package com.bl0ck154.ninebotblocker;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal Java implementation of the NinebotCrypto transport used by newer dashboards.
 *
 * Protocol behaviour was implemented from the public ScooterHacking NinebotCrypto / miauth
 * interoperability documentation. This class is intentionally limited to transport encryption.
 */
public final class NinebotCrypto {
    private static final byte[] FW_DATA = hex("97CFB802844143DE56002B3B34780A5D");

    private byte[] name = new byte[0];
    private byte[] bleData;
    private byte[] appData;
    private byte[] shaKey = new byte[16];
    private int counter;

    public NinebotCrypto(String deviceName) {
        setName(deviceName);
    }

    public void setName(String deviceName) {
        name = (deviceName == null ? "Unnamed" : deviceName).getBytes(StandardCharsets.UTF_8);
        shaKey = calcSha1Key(name, FW_DATA);
        bleData = null;
        appData = null;
        counter = 0;
    }

    public void setBleData(byte[] value) {
        if (value == null || value.length < 16) {
            throw new IllegalArgumentException("BLE key must contain 16 bytes");
        }
        bleData = Arrays.copyOf(value, 16);
        shaKey = calcSha1Key(name, bleData);
    }

    public void setAppData(byte[] value) {
        if (value == null || value.length != 16 || bleData == null) {
            throw new IllegalArgumentException("App key requires a 16-byte key and BLE key");
        }
        appData = Arrays.copyOf(value, 16);
        shaKey = calcSha1Key(appData, bleData);
    }

    public int counter() {
        return counter;
    }

    public byte[] encrypt(byte[] plainPacket) {
        if (plainPacket == null || plainPacket.length < 3) {
            throw new IllegalArgumentException("Invalid Ninebot packet");
        }

        byte[] payload = Arrays.copyOfRange(plainPacket, 3, plainPacket.length);
        byte[] result = new byte[plainPacket.length + 6];
        System.arraycopy(plainPacket, 0, result, 0, 3);

        if (counter == 0 || bleData == null) {
            byte[] encrypted = cryptoFirst(payload);
            System.arraycopy(encrypted, 0, result, 3, encrypted.length);
            int p = 3 + encrypted.length;
            result[p] = 0;
            result[p + 1] = 0;
            byte[] crc = crcFirst(payload);
            result[p + 2] = crc[0];
            result[p + 3] = crc[1];
            result[p + 4] = 0;
            result[p + 5] = 0;
        } else {
            counter = (counter + 1) & 0xFFFF;
            if (counter == 0) counter = 1;

            byte[] encrypted = cryptoNext(payload, counter);
            System.arraycopy(encrypted, 0, result, 3, encrypted.length);
            byte[] crc = crcNext(plainPacket, counter);
            int p = 3 + encrypted.length;
            System.arraycopy(crc, 0, result, p, 4);
            result[p + 4] = (byte) ((counter >>> 8) & 0xFF);
            result[p + 5] = (byte) (counter & 0xFF);
        }
        return result;
    }

    public byte[] decrypt(byte[] encryptedPacket) {
        if (encryptedPacket == null || encryptedPacket.length < 9) {
            throw new IllegalArgumentException("Encrypted Ninebot packet is too short");
        }

        int payloadLength = encryptedPacket.length - 9;
        if (payloadLength < 0) throw new IllegalArgumentException("Invalid encrypted packet");
        byte[] encryptedPayload = Arrays.copyOfRange(encryptedPacket, 3, 3 + payloadLength);

        int responseCounter = ((encryptedPacket[encryptedPacket.length - 2] & 0xFF) << 8)
                | (encryptedPacket[encryptedPacket.length - 1] & 0xFF);
        counter = responseCounter;

        byte[] decrypted;
        if (counter == 0 || bleData == null) {
            decrypted = cryptoFirst(encryptedPayload);
        } else {
            decrypted = cryptoNext(encryptedPayload, counter);
        }

        byte[] result = new byte[3 + decrypted.length];
        System.arraycopy(encryptedPacket, 0, result, 0, 3);
        System.arraycopy(decrypted, 0, result, 3, decrypted.length);
        return result;
    }

    private byte[] cryptoFirst(byte[] data) {
        byte[] result = new byte[data.length];
        byte[] keyStream = aesEcb(FW_DATA, shaKey);
        for (int offset = 0; offset < data.length; offset += 16) {
            int n = Math.min(16, data.length - offset);
            for (int i = 0; i < n; i++) {
                result[offset + i] = (byte) (data[offset + i] ^ keyStream[i]);
            }
        }
        return result;
    }

    private byte[] cryptoNext(byte[] data, int messageCounter) {
        byte[] result = new byte[data.length];
        byte[] aesData = baseCounterBlock(messageCounter);
        for (int offset = 0, block = 1; offset < data.length; offset += 16, block++) {
            aesData[15] = (byte) block;
            byte[] keyStream = aesEcb(aesData, shaKey);
            int n = Math.min(16, data.length - offset);
            for (int i = 0; i < n; i++) {
                result[offset + i] = (byte) (data[offset + i] ^ keyStream[i]);
            }
        }
        return result;
    }

    private byte[] crcNext(byte[] plainPacket, int messageCounter) {
        byte[] aesData = baseCounterBlock(messageCounter);
        aesData[0] = 0x59;
        aesData[15] = (byte) (plainPacket.length - 3);

        byte[] chain = aesEcb(aesData, shaKey);
        byte[] block = new byte[16];
        System.arraycopy(plainPacket, 0, block, 0, Math.min(3, plainPacket.length));
        chain = aesEcb(xor16(block, chain), shaKey);

        int offset = 3;
        while (offset < plainPacket.length) {
            block = new byte[16];
            int n = Math.min(16, plainPacket.length - offset);
            System.arraycopy(plainPacket, offset, block, 0, n);
            chain = aesEcb(xor16(block, chain), shaKey);
            offset += n;
        }

        aesData[0] = 0x01;
        aesData[15] = 0;
        byte[] tail = aesEcb(aesData, shaKey);
        return new byte[]{
                (byte) (tail[0] ^ chain[0]),
                (byte) (tail[1] ^ chain[1]),
                (byte) (tail[2] ^ chain[2]),
                (byte) (tail[3] ^ chain[3])
        };
    }

    private byte[] baseCounterBlock(int messageCounter) {
        if (bleData == null) throw new IllegalStateException("BLE key is not initialized");
        byte[] data = new byte[16];
        data[0] = 0x01;
        data[1] = (byte) ((messageCounter >>> 24) & 0xFF);
        data[2] = (byte) ((messageCounter >>> 16) & 0xFF);
        data[3] = (byte) ((messageCounter >>> 8) & 0xFF);
        data[4] = (byte) (messageCounter & 0xFF);
        System.arraycopy(bleData, 0, data, 5, 8);
        return data;
    }

    private static byte[] crcFirst(byte[] data) {
        int sum = 0;
        for (byte b : data) sum = (sum + (b & 0xFF)) & 0xFFFF;
        int crc = (~sum) & 0xFFFF;
        return new byte[]{(byte) (crc & 0xFF), (byte) ((crc >>> 8) & 0xFF)};
    }

    private static byte[] calcSha1Key(byte[] first, byte[] second) {
        try {
            byte[] input = new byte[32];
            System.arraycopy(first, 0, input, 0, Math.min(16, first.length));
            System.arraycopy(second, 0, input, 16, Math.min(16, second.length));
            return Arrays.copyOf(MessageDigest.getInstance("SHA-1").digest(input), 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static byte[] aesEcb(byte[] block, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }

    private static byte[] xor16(byte[] a, byte[] b) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) out[i] = (byte) (a[i] ^ b[i]);
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
