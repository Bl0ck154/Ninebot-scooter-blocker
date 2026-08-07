package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NinebotProtocolTest {
    @Test
    public void buildsDocumentedLockWritePacket() {
        byte[] expected = new byte[] {
                0x5A, (byte) 0xA5,
                0x02,
                0x3D,
                0x20,
                0x02,
                0x70,
                0x01, 0x00,
                0x2D, (byte) 0xFF
        };

        assertArrayEquals(expected,
                NinebotProtocol.lockPacket(NinebotProtocol.SOURCE_PC, true));
    }

    @Test
    public void recognizesPositiveLockAck() {
        byte[] ack = new byte[] {
                0x5A, (byte) 0xA5,
                0x01,
                0x20,
                0x3D,
                0x05,
                0x70,
                0x01,
                0x2B, (byte) 0xFF
        };
        assertTrue(NinebotProtocol.isPositiveWriteAck(ack, NinebotProtocol.REG_LOCK));

        ack[7] = 0;
        assertFalse(NinebotProtocol.isPositiveWriteAck(ack, NinebotProtocol.REG_LOCK));
    }
}
