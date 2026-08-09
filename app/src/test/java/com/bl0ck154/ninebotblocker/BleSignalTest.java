package com.bl0ck154.ninebotblocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class BleSignalTest {
    @Test public void mapsRssiToCompactBars() {
        assertEquals("—", BleSignal.bars(null));
        assertEquals("▂▄▆█", BleSignal.bars(-55));
        assertEquals("▂▄▆", BleSignal.bars(-70));
        assertEquals("▂▄", BleSignal.bars(-80));
        assertEquals("▂", BleSignal.bars(-92));
    }

    @Test public void marksOnlyWeakSignalsRed() {
        assertFalse(BleSignal.weak(-75));
        assertTrue(BleSignal.weak(-76));
    }
}
