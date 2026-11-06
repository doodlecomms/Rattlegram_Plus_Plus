package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RigFrequenciesTest {
    @Test
    public void formatShowsThreeDecimalsOrMoreWhenNeeded() {
        assertEquals("146.550 MHz", RigFrequencies.format(146_550_000));
        assertEquals("145.5125 MHz", RigFrequencies.format(145_512_500));
        assertEquals("7.074 MHz", RigFrequencies.format(7_074_000));
        assertEquals("146.52025 MHz", RigFrequencies.format(146_520_250));
    }

    @Test
    public void stepsMoveOneGridLine() {
        assertEquals(146_555_000, RigFrequencies.step(146_550_000, 5_000, +1));
        assertEquals(146_545_000, RigFrequencies.step(146_550_000, 5_000, -1));
        assertEquals(146_575_000, RigFrequencies.step(146_550_000, 25_000, +1));
        assertEquals(147_000_000, RigFrequencies.step(146_550_000, 1_000_000, +1));
    }

    @Test
    public void offGridFrequenciesGoToTheNearestLineInThatDirection() {
        assertEquals(146_525_000, RigFrequencies.step(146_521_000, 12_500, +1));
        assertEquals(146_512_500, RigFrequencies.step(146_521_000, 12_500, -1));
    }

    @Test
    public void stepNeverGoesBelowZero() {
        assertEquals(0, RigFrequencies.step(3_000, 5_000, -1));
    }

    @Test
    public void typedFrequenciesAreParsedInMhz() {
        assertEquals(146_550_000, RigFrequencies.parseMhz("146.550"));
        assertEquals(146_550_000, RigFrequencies.parseMhz(" 146,55 "));
        assertEquals(-1, RigFrequencies.parseMhz("abc"));
        assertEquals(-1, RigFrequencies.parseMhz("0"));
        assertEquals(-1, RigFrequencies.parseMhz(""));
    }

    @Test
    public void savedListSurvivesStoring() {
        List<RigFrequencies.Saved> list = new ArrayList<>();
        list = RigFrequencies.withSaved(list, 146_550_000, "Simplex");
        list = RigFrequencies.withSaved(list, 146_520_000, "Calling");
        List<RigFrequencies.Saved> back = RigFrequencies.parseSaved(RigFrequencies.serializeSaved(list));
        assertEquals(2, back.size());
        assertEquals("sorted by frequency", 146_520_000, back.get(0).hz);
        assertEquals("Calling", back.get(0).name);
        assertEquals("Simplex", back.get(1).name);
    }

    @Test
    public void savingTheSameFrequencyRenamesIt() {
        List<RigFrequencies.Saved> list = RigFrequencies.withSaved(new ArrayList<>(), 146_550_000, "A");
        list = RigFrequencies.withSaved(list, 146_550_000, "B");
        assertEquals(1, list.size());
        assertEquals("B", list.get(0).name);
    }

    @Test
    public void namesCannotBreakTheStoredFormat() {
        List<RigFrequencies.Saved> list = RigFrequencies.withSaved(new ArrayList<>(), 146_550_000, "net\tnight\nline");
        List<RigFrequencies.Saved> back = RigFrequencies.parseSaved(RigFrequencies.serializeSaved(list));
        assertEquals(1, back.size());
        assertEquals("net night line", back.get(0).name);
    }

    @Test
    public void deletingAndBadStoredLines() {
        List<RigFrequencies.Saved> list = RigFrequencies.parseSaved("146550000\tSimplex\ngarbage\n-5\tx\n146520000\t");
        assertEquals(2, list.size());
        assertEquals(1, RigFrequencies.without(list, 146_550_000).size());
        assertTrue(RigFrequencies.parseSaved(null).isEmpty());
    }

    @Test
    public void savedListIsCapped() {
        List<RigFrequencies.Saved> list = new ArrayList<>();
        for (int i = 0; i < RigFrequencies.MAX_SAVED + 5; ++i)
            list = RigFrequencies.withSaved(list, 144_000_000L + i * 5_000L, "");
        assertEquals(RigFrequencies.MAX_SAVED, list.size());
    }

    @Test
    public void onlyOfferedStepsAreAccepted() {
        assertTrue(RigFrequencies.isStep(12_500));
        assertTrue(!RigFrequencies.isStep(7_000));
    }
}
