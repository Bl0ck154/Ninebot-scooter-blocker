package com.bl0ck154.ninebotblocker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NinebotProtocolTest {
    @Test
    public void buildsAuthenticatedLockPlainPacket() {
        byte[] expected = new byte[] {
                0x5A, (byte) 0xA5,
                0x02,
                0x3D,
                0x20,
                0x02,
                0x70,
                0x01, 0x00
        };
        assertArrayEquals(expected, NinebotProtocol.lockPacket());
    }

    @Test
    public void buildsDocumentedInitPacket() {
        byte[] expected = new byte[] {
                0x5A, (byte) 0xA5, 0x00, 0x3D, 0x21, 0x5B, 0x00
        };
        assertArrayEquals(expected, NinebotProtocol.initPacket());
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
                0x01
        };
        assertTrue(NinebotProtocol.isPositiveWriteAck(ack, NinebotProtocol.REG_LOCK));
        ack[7] = 0;
        assertFalse(NinebotProtocol.isPositiveWriteAck(ack, NinebotProtocol.REG_LOCK));
    }
}
