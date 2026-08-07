package com.bl0ck154.ninebotblocker;

import org.junit.Test;

import static org.junit.Assert.*;

public class G30ProtocolTest {
    @Test public void readPacketsKeepKnown5aa5Shape() {
        assertArrayEquals(new byte[]{0x5A, (byte) 0xA5, 0x01, 0x3E, 0x20, 0x01, (byte) 0xB4, 0x02},
                G30Protocol.readBatteryPercent());
        assertArrayEquals(new byte[]{0x5A, (byte) 0xA5, 0x01, 0x3E, 0x22, 0x01, 0x34, 0x02},
                G30Protocol.readBatteryVoltage());
        assertArrayEquals(ShuNinebotProtocol.lockPacket(), G30Protocol.lockPacket());
        assertArrayEquals(ShuNinebotProtocol.unlockPacket(), G30Protocol.unlockPacket());
    }

    @Test public void parsesG30EscTelemetryUnits() {
        ScooterTelemetry t = new ScooterTelemetry();
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_BATTERY, le16(73)), t));
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_SPEED, le16(273)), t));
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_TRIP, le16(1240)), t));
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_RANGE, le16(5850)), t));
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_CONTROLLER_TEMP, le16(315)), t));
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_ODOMETER, le32(12_481_000L)), t));

        assertEquals(Integer.valueOf(73), t.getBatteryPercent());
        assertEquals(27.3, t.getSpeed(), 0.001);
        assertEquals(12.4, t.getTripDistance(), 0.001);
        assertEquals(58.5, t.getRemainingRange(), 0.001);
        assertEquals(31.5, t.getControllerTemperature(), 0.001);
        assertEquals(12_481.0, t.getTotalDistance(), 0.001);
    }

    @Test public void parsesBmsVoltageCurrentTemperatureAndPower() {
        ScooterTelemetry t = new ScooterTelemetry();
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_BMS_VOLTAGE, le16(3810)), t));
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_BMS_CURRENT, le16(-640)), t));
        assertTrue(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_BMS_TEMP, new byte[]{51, 52}), t));

        assertEquals(38.1, t.getBatteryVoltage(), 0.001);
        assertEquals(-6.4, t.getBatteryCurrent(), 0.001);
        assertEquals(243.84, t.getBatteryPower(), 0.001);
        assertEquals(31.0, t.getBatteryTemperature(), 0.001);
    }

    @Test public void rejectsImpossibleBatteryPercentage() {
        ScooterTelemetry t = new ScooterTelemetry();
        assertFalse(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_BATTERY, le16(500)), t));
        assertNull(t.getBatteryPercent());
    }

    @Test public void rejectsImpossibleRange() {
        ScooterTelemetry t = new ScooterTelemetry();
        assertFalse(G30Protocol.applyTelemetryPacket(response(G30Protocol.REG_RANGE, le16(25_000)), t));
        assertNull(t.getRemainingRange());
    }

    private static byte[] response(int register, byte[] payload) {
        byte[] command = new byte[4 + payload.length];
        command[0] = (byte) ShuNinebotProtocol.TARGET_ESC;
        command[1] = (byte) ShuNinebotProtocol.SOURCE_PHONE;
        command[2] = (byte) ShuNinebotProtocol.CMD_READ_ACK;
        command[3] = (byte) register;
        System.arraycopy(payload, 0, command, 4, payload.length);
        return ShuNinebotProtocol.wrapCryptoPlain(command);
    }

    private static byte[] le16(int value) {
        return new byte[]{(byte) value, (byte) (value >>> 8)};
    }

    private static byte[] le32(long value) {
        return new byte[]{(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }
}
