package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LegacyNinebotProtocolTest {
    @Test
    public void buildsLegacyLockPacket() {
        assertArrayEquals(hex("55AA04200370010067FF"), LegacyNinebotProtocol.lockPacket());
    }

    @Test
    public void buildsLegacyFirmwareReadPacket() {
        assertArrayEquals(hex("55AA0320011A02BFFF"), LegacyNinebotProtocol.readFirmwarePacket());
    }

    @Test
    public void buildsLegacyLockStateReadPacket() {
        assertArrayEquals(hex("55AA0320011D02BCFF"), LegacyNinebotProtocol.readLockStatePacket());
    }

    @Test
    public void parsesLockStateBit() {
        byte[] locked = LegacyNinebotProtocol.build(
                LegacyNinebotProtocol.DIRECTION_SCOOTER_REPLY,
                LegacyNinebotProtocol.READ,
                LegacyNinebotProtocol.REG_LOCK_STATE,
                new byte[]{0x02, 0x00});
        assertTrue(LegacyNinebotProtocol.isPacket(locked));
        assertTrue(LegacyNinebotProtocol.lockState(locked));

        byte[] unlocked = LegacyNinebotProtocol.build(
                LegacyNinebotProtocol.DIRECTION_SCOOTER_REPLY,
                LegacyNinebotProtocol.READ,
                LegacyNinebotProtocol.REG_LOCK_STATE,
                new byte[]{0x00, 0x00});
        assertTrue(LegacyNinebotProtocol.isPacket(unlocked));
        assertFalse(LegacyNinebotProtocol.lockState(unlocked));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
