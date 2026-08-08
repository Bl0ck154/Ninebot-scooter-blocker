package com.bl0ck154.ninebotblocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ScooterIdentityTest {
    @Test public void identifiesG30FromSerialEvenWithGenericBleName() {
        assertEquals("Ninebot Max G30",
                ScooterIdentity.displayModel("NBScooter2020", "N4GSD1234C5678"));
    }

    @Test public void keepsUsefulAdvertisedModelNames() {
        assertEquals("Ninebot F2 Pro",
                ScooterIdentity.displayModel("Ninebot F2 Pro", null));
    }

    @Test public void genericNbScooterNameDoesNotPretendToBeG30() {
        assertEquals("Ninebot / Segway Scooter",
                ScooterIdentity.displayModel("NBScooter2020", null));
    }
}
