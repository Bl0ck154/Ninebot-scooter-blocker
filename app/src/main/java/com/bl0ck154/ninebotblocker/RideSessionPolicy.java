package com.bl0ck154.ninebotblocker;

/** Pure ride-splitting rules shared by the tracker and unit tests. */
public final class RideSessionPolicy {
    private static final double MIN_SPEED_KMH = 1.0;
    private static final double MIN_DISTANCE_DELTA_KM = 0.001; // 1 m

    private RideSessionPolicy() {}

    public static boolean isMoving(double distanceDeltaKm, Double speedKmh) {
        if (distanceDeltaKm >= MIN_DISTANCE_DELTA_KM) return true;
        return speedKmh != null && Math.abs(speedKmh) >= MIN_SPEED_KMH;
    }

    public static boolean isExpired(long lastMovementAt, long now, long timeoutMs) {
        return lastMovementAt > 0L && timeoutMs > 0L && now - lastMovementAt >= timeoutMs;
    }
}
