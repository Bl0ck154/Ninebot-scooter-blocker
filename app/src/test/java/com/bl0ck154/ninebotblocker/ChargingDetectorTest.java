package com.bl0ck154.ninebotblocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class ChargingDetectorTest {
    @Test public void requiresTwoSeparatedPositiveCurrentSamples() {
        ChargingDetector detector = new ChargingDetector();
        long t = 10_000L;
        assertNull(detector.update(2.5, 0.0, t, true));
        // Ordinary telemetry with the same cached current must not confirm charging.
        assertNull(detector.update(2.5, 0.0, t + 6_000L, false));
        assertEquals(Boolean.TRUE, detector.update(2.4, 0.0, t + 8_000L, true));
    }

    @Test public void movingPositiveCurrentDoesNotLookLikeCharging() {
        ChargingDetector detector = new ChargingDetector();
        long t = 20_000L;
        assertEquals(Boolean.FALSE, detector.update(2.0, 12.0, t, true));
        assertEquals(Boolean.FALSE, detector.update(2.0, 12.0, t + 8_000L, true));
    }

    @Test public void establishedChargeSurvivesTaperThenStopsOnLaterFreshSample() {
        ChargingDetector detector = new ChargingDetector();
        long t = 30_000L;
        detector.update(2.5, 0.0, t, true);
        assertEquals(Boolean.TRUE, detector.update(2.5, 0.0, t + 8_000L, true));
        assertEquals(Boolean.TRUE, detector.update(0.01, 0.0, t + 9_000L, true));
        assertEquals(Boolean.TRUE, detector.update(0.01, 0.0, t + 20_000L, false));
        assertEquals(Boolean.FALSE, detector.update(0.01, 0.0, t + 20_000L, true));
    }

    @Test public void dischargeClearsChargingImmediately() {
        ChargingDetector detector = new ChargingDetector();
        long t = 40_000L;
        detector.update(2.5, 0.0, t, true);
        detector.update(2.5, 0.0, t + 8_000L, true);
        assertEquals(Boolean.FALSE, detector.update(-1.0, 0.0, t + 8_500L, true));
    }
}
