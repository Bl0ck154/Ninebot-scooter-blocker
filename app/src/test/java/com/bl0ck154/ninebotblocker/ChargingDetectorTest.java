package com.bl0ck154.ninebotblocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ChargingDetectorTest {
    @Test public void requiresSustainedPositiveCurrentBeforeCharging() {
        ChargingDetector detector = new ChargingDetector();
        long t = 10_000L;
        assertNull(detector.update(2.5, 0.0, t));
        assertNull(detector.update(2.4, 0.0, t + 3_999L));
        assertEquals(Boolean.TRUE, detector.update(2.4, 0.0, t + 4_000L));
    }

    @Test public void movingPositiveCurrentDoesNotLookLikeCharging() {
        ChargingDetector detector = new ChargingDetector();
        long t = 20_000L;
        assertNull(detector.update(2.0, 12.0, t));
        assertEquals(Boolean.FALSE, detector.update(2.0, 12.0, t + 4_500L));
    }

    @Test public void establishedChargeSurvivesOneTaperedReadThenStops() {
        ChargingDetector detector = new ChargingDetector();
        long t = 30_000L;
        detector.update(2.5, 0.0, t);
        assertEquals(Boolean.TRUE, detector.update(2.5, 0.0, t + 4_000L));
        assertEquals(Boolean.TRUE, detector.update(0.01, 0.0, t + 5_000L));
        assertEquals(Boolean.FALSE, detector.update(0.01, 0.0, t + 13_000L));
    }

    @Test public void dischargeClearsChargingImmediately() {
        ChargingDetector detector = new ChargingDetector();
        long t = 40_000L;
        detector.update(2.5, 0.0, t);
        detector.update(2.5, 0.0, t + 4_000L);
        assertEquals(Boolean.FALSE, detector.update(-1.0, 0.0, t + 4_500L));
    }
}
