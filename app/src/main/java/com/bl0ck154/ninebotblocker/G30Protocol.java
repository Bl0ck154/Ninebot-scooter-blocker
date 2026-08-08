package com.bl0ck154.ninebotblocker;

/**
 * Legacy Ninebot/G30 register layer. Authentication and lock/unlock delegate to the
 * existing SHU implementation so the known-good byte sequence is preserved.
 * The same quick register map is shared by several older Ninebot scooters; G30
 * remains the hardware-tested baseline.
 */
public final class G30Protocol {
    public static final int TARGET_BMS = 0x22;

    public static final int REG_RANGE = 0x25;
    public static final int REG_CONTROLLER_TEMP = 0x3E;
    public static final int REG_STATUS = 0xB2;
    public static final int REG_BATTERY = 0xB4;
    public static final int REG_SPEED = 0xB5;
    public static final int REG_ODOMETER = 0xB7;
    public static final int REG_TRIP = 0xB9;

    public static final int REG_BMS_CURRENT = 0x33;
    public static final int REG_BMS_VOLTAGE = 0x34;
    public static final int REG_BMS_TEMP = 0x35;

    // Ninebot boolean state word: NB_BOOLMARK_LOCK.
    private static final int STATUS_LOCKED_MASK = 0x0002;

    private G30Protocol() {}

    public static byte[] initPacket() { return ShuNinebotProtocol.initPacket(); }
    public static byte[] pingPacket() { return ShuNinebotProtocol.pingPacket(); }
    public static byte[] pairPacket(byte[] serial) { return ShuNinebotProtocol.pairPacket(serial); }
    public static byte[] lockPacket() { return ShuNinebotProtocol.lockPacket(); }
    public static byte[] unlockPacket() { return ShuNinebotProtocol.unlockPacket(); }

    public static byte[] readLockStatus() { return readEsc(REG_STATUS, 2); }
    public static byte[] readBatteryPercent() { return readEsc(REG_BATTERY, 2); }
    public static byte[] readSpeed() { return readEsc(REG_SPEED, 2); }
    public static byte[] readOdometer() { return readEsc(REG_ODOMETER, 4); }
    public static byte[] readTripDistance() { return readEsc(REG_TRIP, 2); }
    public static byte[] readRemainingRange() { return readEsc(REG_RANGE, 2); }
    public static byte[] readControllerTemperature() { return readEsc(REG_CONTROLLER_TEMP, 2); }
    public static byte[] readBatteryCurrent() { return readBms(REG_BMS_CURRENT, 2); }
    public static byte[] readBatteryVoltage() { return readBms(REG_BMS_VOLTAGE, 2); }
    public static byte[] readBatteryTemperature() { return readBms(REG_BMS_TEMP, 2); }

    public static byte[] readEsc(int register, int length) {
        return readRegister(ShuNinebotProtocol.TARGET_ESC, register, length);
    }

    public static byte[] readBms(int register, int length) {
        return readRegister(TARGET_BMS, register, length);
    }

    private static byte[] readRegister(int target, int register, int length) {
        if (length < 1 || length > 0x38) throw new IllegalArgumentException("Invalid read length");
        return ShuNinebotProtocol.wrapCryptoPlain(new byte[]{
                (byte) ShuNinebotProtocol.SOURCE_PHONE,
                (byte) target,
                (byte) ShuNinebotProtocol.CMD_READ_REGISTER,
                (byte) register,
                (byte) length
        });
    }

    /** Applies one read response to the central telemetry model. */
    public static boolean applyTelemetryPacket(byte[] packet, ScooterTelemetry telemetry) {
        if (telemetry == null || !ShuNinebotProtocol.isPacket(packet)) return false;
        int command = ShuNinebotProtocol.command(packet);
        if (command != ShuNinebotProtocol.CMD_READ_REGISTER
                && command != ShuNinebotProtocol.CMD_READ_ACK) return false;

        int index = ShuNinebotProtocol.index(packet);
        byte[] payload = ShuNinebotProtocol.payload(packet);
        switch (index) {
            case REG_STATUS: {
                if (payload.length < 2) return false;
                int flags = u16(payload, 0);
                telemetry.setLocked((flags & STATUS_LOCKED_MASK) != 0);
                return true;
            }
            case REG_BATTERY: {
                if (payload.length < 2) return false;
                int value = u16(payload, 0);
                if (value < 0 || value > 100) return false;
                telemetry.setBatteryPercent(value);
                return true;
            }
            case REG_SPEED: {
                if (payload.length < 2) return false;
                double value = Math.abs(s16(payload, 0)) / 10.0;
                if (value > 120.0) return false;
                telemetry.setSpeed(value);
                return true;
            }
            case REG_ODOMETER: {
                if (payload.length < 4) return false;
                telemetry.setTotalDistance(u32(payload, 0) / 1000.0);
                return true;
            }
            case REG_TRIP: {
                if (payload.length < 2) return false;
                telemetry.setTripDistance(u16(payload, 0) / 100.0);
                return true;
            }
            case REG_RANGE: {
                if (payload.length < 2) return false;
                double value = u16(payload, 0) / 100.0;
                if (value < 0.0 || value > 200.0) return false;
                telemetry.setRemainingRange(value);
                return true;
            }
            case REG_CONTROLLER_TEMP: {
                if (payload.length < 2) return false;
                double value = s16(payload, 0) / 10.0;
                if (value < -40 || value > 150) return false;
                telemetry.setControllerTemperature(value);
                return true;
            }
            case REG_BMS_CURRENT: {
                if (payload.length < 2) return false;
                telemetry.setBatteryCurrent(s16(payload, 0) / 100.0);
                return true;
            }
            case REG_BMS_VOLTAGE: {
                if (payload.length < 2) return false;
                double value = s16(payload, 0) / 100.0;
                if (value < 0 || value > 100) return false;
                telemetry.setBatteryVoltage(value);
                return true;
            }
            case REG_BMS_TEMP: {
                if (payload.length < 2) return false;
                int sensor = payload[0] & 0xFF;
                if (sensor > 119) return false;
                telemetry.setBatteryTemperature((double) sensor - 20.0);
                return true;
            }
            default:
                return false;
        }
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static int s16(byte[] data, int offset) {
        return (short) u16(data, offset);
    }

    private static long u32(byte[] data, int offset) {
        return ((long) data[offset] & 0xFF)
                | (((long) data[offset + 1] & 0xFF) << 8)
                | (((long) data[offset + 2] & 0xFF) << 16)
                | (((long) data[offset + 3] & 0xFF) << 24);
    }
}
