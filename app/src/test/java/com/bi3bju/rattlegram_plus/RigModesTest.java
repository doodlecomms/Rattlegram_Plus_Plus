package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RigModesTest {
    @Test
    public void voiceModesMapToTheirDataModes() {
        assertEquals("FM to packet FM (the FT-857's PKT)", RigModes.PKTFM, RigModes.dataModeFor(RigModes.FM));
        assertEquals(RigModes.PKTUSB, RigModes.dataModeFor(RigModes.USB));
        assertEquals(RigModes.PKTLSB, RigModes.dataModeFor(RigModes.LSB));
    }

    @Test
    public void modesWithoutADataModeAreLeftAlone() {
        assertEquals(0, RigModes.dataModeFor(RigModes.AM));
        assertEquals(0, RigModes.dataModeFor(RigModes.CW));
        assertEquals(0, RigModes.dataModeFor(0));
    }

    @Test
    public void dataModesAreRecognised() {
        assertTrue(RigModes.isDataMode(RigModes.PKTFM));
        assertTrue(RigModes.isDataMode(RigModes.PKTUSB));
        assertTrue(RigModes.isDataMode(RigModes.PKTLSB));
        assertFalse(RigModes.isDataMode(RigModes.FM));
        assertFalse(RigModes.isDataMode(RigModes.USB));
    }

    @Test
    public void valuesMatchHamlib() {
        // rmode_t bits from hamlib/rig.h
        assertEquals(1L << 5, RigModes.FM);
        assertEquals(1L << 12, RigModes.PKTFM);
    }
}
