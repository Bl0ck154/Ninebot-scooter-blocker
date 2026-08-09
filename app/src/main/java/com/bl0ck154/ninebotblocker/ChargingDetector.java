package com.bl0ck154.ninebotblocker;

/**
 * Conservative charging inference for the hardware-tested G30 path.
 *
 * The app already observes BMS current. On the tested G30 discharge current is negative, while
 * charger current is positive. Start/stop confirmation only advances on fresh BMS current reads,
 * so a stale regenerative-braking sample cannot turn into a charging state a few seconds later.
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

    Boolean update(Double currentA, Double speedKmh, long nowMs, boolean freshCurrent) {
        boolean stationary = speedKmh == null || Math.abs(speedKmh) < STATIONARY_KMH;
        boolean moving = speedKmh != null && Math.abs(speedKmh) >= CLEAR_MOVING_KMH;
        boolean positiveChargeCurrent = currentA != null && currentA > START_CURRENT_A;
        boolean clearlyDischarging = currentA != null && currentA < -START_CURRENT_A;

        if (moving) {
            candidateSince = 0L;
            inactiveSince = 0L;
            charging = Boolean.FALSE;
            return charging;
        }

        if (stationary && positiveChargeCurrent) {
            inactiveSince = 0L;
            if (freshCurrent) {
                if (candidateSince == 0L) candidateSince = nowMs;
                else if (nowMs - candidateSince >= START_CONFIRM_MS) charging = Boolean.TRUE;
            }
            return charging;
        }

        // Becoming non-stationary, or receiving a fresh non-positive sample, cancels startup.
        if (!stationary || freshCurrent) candidateSince = 0L;

        if (!Boolean.TRUE.equals(charging)) {
            if (freshCurrent && clearlyDischarging) charging = Boolean.FALSE;
            return charging;
        }

        // Once charging is established, a positive tapered current keeps it alive. A zero/very
        // small current needs two separated current samples (or enough elapsed time between them)
        // before charging is cleared, while real discharge clears it immediately.
        if (!freshCurrent) return charging;
        if (clearlyDischarging) {
            charging = Boolean.FALSE;
            inactiveSince = 0L;
            return charging;
        }
        if (stationary && currentA != null && currentA > HOLD_CURRENT_A) {
            inactiveSince = 0L;
            return charging;
        }
        if (inactiveSince == 0L) inactiveSince = nowMs;
        else if (nowMs - inactiveSince >= STOP_CONFIRM_MS) {
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
