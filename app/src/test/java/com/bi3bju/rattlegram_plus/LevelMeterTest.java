package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class LevelMeterTest {
    private static short[] chunk(int peak) {
        short[] s = new short[160];
        Arrays.fill(s, (short) 0);
        s[50] = (short) peak;
        s[51] = (short) -peak;
        return s;
    }

    @Test
    public void decibelsRelativeToFullScale() {
        assertEquals(0, LevelMeter.toDb(32767), 0.01);
        assertEquals(-6.02, LevelMeter.toDb(16384), 0.01);
        assertEquals(-20, LevelMeter.toDb(3277), 0.01);
        assertEquals(LevelMeter.FLOOR_DB, LevelMeter.toDb(0), 0);
    }

    @Test
    public void zones() {
        assertEquals(LevelMeter.Zone.CLIPPING, LevelMeter.zoneFor(0));
        assertEquals(LevelMeter.Zone.LOUD, LevelMeter.zoneFor(-3));
        assertEquals(LevelMeter.Zone.GOOD, LevelMeter.zoneFor(-20));
        assertEquals(LevelMeter.Zone.LOW, LevelMeter.zoneFor(-50));
        assertEquals(LevelMeter.Zone.SILENT, LevelMeter.zoneFor(-80));
    }

    @Test
    public void peakIsHeldAndFallsBackSlowly() {
        LevelMeter m = new LevelMeter();
        m.add(chunk(3277), 160, 0.02);
        assertEquals(-20, m.levelDb(), 0.01);
        // One second of silence lets the held peak fall by FALL_DB_PER_SECOND
        for (int i = 0; i < 50; ++i)
            m.add(chunk(0), 160, 0.02);
        assertEquals(-20 - LevelMeter.FALL_DB_PER_SECOND, m.levelDb(), 0.01);
        for (int i = 0; i < 500; ++i)
            m.add(chunk(0), 160, 0.02);
        assertEquals(LevelMeter.FLOOR_DB, m.levelDb(), 0);
        assertEquals(LevelMeter.Zone.SILENT, m.zone());
        assertEquals(0, m.fill(), 0);
    }

    @Test
    public void clippingStaysRedUntilTheLevelHasDroppedBelowLoud() {
        LevelMeter m = new LevelMeter();
        m.add(chunk(32767), 160, 0.02);
        assertEquals(LevelMeter.Zone.CLIPPING, m.zone());
        m.add(chunk(3277), 160, 0.02);
        assertEquals("still held near full scale", LevelMeter.Zone.CLIPPING, m.zone());
        for (int i = 0; i < 25; ++i)
            m.add(chunk(3277), 160, 0.02);
        assertEquals(LevelMeter.Zone.GOOD, m.zone());
    }

    @Test
    public void onlyTheGivenSamplesCount() {
        LevelMeter m = new LevelMeter();
        short[] s = chunk(0);
        s[150] = 32767;
        m.add(s, 100, 0.02);
        assertTrue(m.levelDb() <= LevelMeter.FLOOR_DB);
    }
}
