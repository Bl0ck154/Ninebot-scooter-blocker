package com.bl0ck154.ninebotblocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RideSessionPolicyTest {
    @Test public void stationaryTelemetryDoesNotCountAsMovement() {
        assertFalse(RideSessionPolicy.isMoving(0.0, 0.0));
        assertFalse(RideSessionPolicy.isMoving(0.0005, 0.5));
    }

    @Test public void odometerOrSpeedCanMarkMovement() {
        assertTrue(RideSessionPolicy.isMoving(0.001, 0.0));
        assertTrue(RideSessionPolicy.isMoving(0.0, 2.0));
    }

    @Test public void rideExpiresFromLastMovementNotConnectionActivity() {
        long lastMove = 1_000_000L;
        long twentyMinutes = 20L * 60L * 1000L;
        assertFalse(RideSessionPolicy.isExpired(lastMove, lastMove + twentyMinutes - 1L, twentyMinutes));
        assertTrue(RideSessionPolicy.isExpired(lastMove, lastMove + twentyMinutes, twentyMinutes));
        assertTrue(RideSessionPolicy.isExpired(lastMove, lastMove + 3L * 60L * 60L * 1000L, twentyMinutes));
    }
}
