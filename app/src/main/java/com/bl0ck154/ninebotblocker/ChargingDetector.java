package com.bl0ck154.ninebotblocker;

/**
 * Conservative charging inference for the hardware-tested G30 path.
 *
 * The app already observes BMS current. On the tested G30 discharge current is negative, while
 * charger current is positive. We intentionally require sustained positive current while the
 * scooter is stationary so a short regenerative-braking pulse is not shown as charging.
 */
final class ChargingDetector {
    static final double START_CURRENT_A = 0.20;
    static final double HOLD_CURRENT_A = 0.05;
    static final double STATIONARY_KMH = 0.8;
    static final double CLEAR_MOVING_KMH = 2.0;
    static final long START_CONFIRM_MS = 4_000L;
    static final long STOP_CONFIRM_MS = 8_000L;

    private Boolean charging;
    private long candidateSince;
    private long inactiveSince;

    Boolean update(Double currentA, Double speedKmh, long nowMs) {
        boolean stationary = speedKmh == null || Math.abs(speedKmh) < STATIONARY_KMH;
        boolean positiveChargeCurrent = currentA != null && currentA > START_CURRENT_A;
        boolean startCandidate = stationary && positiveChargeCurrent;

        if (startCandidate) {
            inactiveSince = 0L;
            if (candidateSince == 0L) candidateSince = nowMs;
            if (nowMs - candidateSince >= START_CONFIRM_MS) charging = Boolean.TRUE;
            return charging;
        }

        candidateSince = 0L;
        boolean moving = speedKmh != null && Math.abs(speedKmh) >= CLEAR_MOVING_KMH;
        boolean clearlyDischarging = currentA != null && currentA < -START_CURRENT_A;

        if (Boolean.TRUE.equals(charging)) {
            // Charger current tapers close to 100%, so don't drop the state on one near-zero read.
            boolean stillPlausiblyCharging = stationary && currentA != null && currentA > HOLD_CURRENT_A;
            if (stillPlausiblyCharging) {
                inactiveSince = 0L;
            } else {
                if (inactiveSince == 0L) inactiveSince = nowMs;
                if (moving || clearlyDischarging || nowMs - inactiveSince >= STOP_CONFIRM_MS) {
                    charging = Boolean.FALSE;
                    inactiveSince = 0L;
                }
            }
        } else if (moving || clearlyDischarging) {
            charging = Boolean.FALSE;
            inactiveSince = 0L;
        }

        return charging;
    }

    void reset() {
        charging = null;
        candidateSince = 0L;
        inactiveSince = 0L;
    }
}
