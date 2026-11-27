package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReplayGuardTest {
    private static final long NOW = 1_795_800_000_000L; // late 2026, in milliseconds

    @Test
    public void stampRoundTrips() {
        String s = ReplayGuard.stamp("hello", NOW);
        assertEquals(ReplayGuard.OVERHEAD + 5, s.length());
        assertTrue(s.startsWith(ReplayGuard.PREFIX));
        assertEquals(NOW / 1000, ReplayGuard.stampOf(s));
        assertEquals("hello", ReplayGuard.strip(s));
    }

    @Test
    public void stampGoesBeforeAHiddenCallSign() {
        String s = ReplayGuard.stamp(HiddenCall.wrap("KE2CZZ", "hi"), NOW);
        String[] hidden = HiddenCall.unwrap(ReplayGuard.strip(s));
        assertEquals("KE2CZZ", hidden[0]);
        assertEquals("hi", hidden[1]);
    }

    @Test
    public void textWithoutAStampIsLeftAlone() {
        assertEquals(-1, ReplayGuard.stampOf("hello"));
        assertEquals("hello", ReplayGuard.strip("hello"));
        assertEquals("~T:short\nhello", ReplayGuard.strip("~T:short\nhello"));
        assertEquals("upper case is not ours", -1, ReplayGuard.stampOf("~T:0TPVSZK\nx"));
        assertNull(ReplayGuard.strip(null));
    }

    @Test
    public void acceptedOnceWithinTheWindow() {
        ReplayGuard g = new ReplayGuard();
        String plain = ReplayGuard.stamp("hello", NOW - 30_000);
        assertEquals(ReplayGuard.Verdict.OK, g.check(plain, "CIPHER1", NOW));
        assertEquals(ReplayGuard.Verdict.REPLAYED, g.check(plain, "CIPHER1", NOW + 5_000));
        assertEquals("another encryption of the same text", ReplayGuard.Verdict.OK, g.check(plain, "CIPHER2", NOW + 5_000));
    }

    @Test
    public void tooOldOrFromTheFutureIsRejected() {
        ReplayGuard g = new ReplayGuard();
        g.windowSeconds = 120;
        assertEquals(ReplayGuard.Verdict.TOO_OLD, g.check(ReplayGuard.stamp("x", NOW - 121_000), "A", NOW));
        assertEquals(ReplayGuard.Verdict.FROM_FUTURE, g.check(ReplayGuard.stamp("x", NOW + 121_000), "B", NOW));
        assertEquals(ReplayGuard.Verdict.OK, g.check(ReplayGuard.stamp("x", NOW + 60_000), "C", NOW));
        assertEquals(ReplayGuard.Verdict.NO_STAMP, g.check("x", "D", NOW));
        assertEquals(59, g.secondsOutside(ReplayGuard.stamp("x", NOW - 179_000), NOW));
    }

    @Test
    public void aReplayAfterTheWindowIsTooOldNotForgotten() {
        ReplayGuard g = new ReplayGuard();
        g.windowSeconds = 30;
        String plain = ReplayGuard.stamp("x", NOW);
        assertEquals(ReplayGuard.Verdict.OK, g.check(plain, "A", NOW));
        assertEquals(ReplayGuard.Verdict.TOO_OLD, g.check(plain, "A", NOW + 31_000));
    }

    @Test
    public void laterUnlockChecksAgainstTheTimeItArrived() {
        ReplayGuard g = new ReplayGuard();
        String plain = ReplayGuard.stamp("x", NOW);
        assertEquals(ReplayGuard.Verdict.OK, g.checkTime(plain, NOW + 10_000));
        assertEquals(ReplayGuard.Verdict.TOO_OLD, g.checkTime(plain, NOW + 3_600_000));
    }

    @Test
    public void ourOwnMessageComingBackIsARepeat() {
        ReplayGuard g = new ReplayGuard();
        g.rememberSent("MINE", NOW);
        assertEquals(ReplayGuard.Verdict.REPLAYED, g.check(ReplayGuard.stamp("x", NOW), "MINE", NOW + 3_000));
    }

    @Test
    public void windowNames() {
        assertEquals("30 s", ReplayGuard.describe(30));
        assertEquals("2 min", ReplayGuard.describe(120));
        assertEquals("1 h", ReplayGuard.describe(3600));
    }
}
