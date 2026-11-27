package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class QuickMessageTest {
    @Test
    public void nothingSavedGivesTheStarterSet() {
        List<QuickMessage> list = QuickMessage.fromJson(null);
        assertFalse(list.isEmpty());
        for (QuickMessage q : list)
            assertTrue(q.hasContent());
    }

    @Test
    public void anEmptySavedListStaysEmpty() {
        assertTrue("the user deleted them all", QuickMessage.fromJson("[]").isEmpty());
        assertTrue("unreadable", QuickMessage.fromJson("not json").isEmpty());
    }

    @Test
    public void savedAndReadBackUnchanged() {
        QuickMessage q = new QuickMessage("Net", "@KE2CZZ1 checking in", true);
        q.encrypt = true;
        q.repeats = 3;
        q.gapSeconds = 30;
        q.confirm = false;
        List<QuickMessage> back = QuickMessage.fromJson(QuickMessage.toJson(Arrays.asList(q, new QuickMessage("", "OK", false))));
        assertEquals(2, back.size());
        QuickMessage r = back.get(0);
        assertEquals("Net", r.label);
        assertEquals("@KE2CZZ1 checking in", r.text);
        assertTrue(r.encrypt);
        assertTrue(r.addLocation);
        assertEquals(3, r.repeats);
        assertEquals(30, r.gapSeconds);
        assertFalse(r.confirm);
        assertEquals("OK", back.get(1).text);
    }

    @Test
    public void numbersAreKeptInRange() {
        QuickMessage q = new QuickMessage("x", "y", false);
        q.repeats = 99;
        q.gapSeconds = 1;
        q.clamp();
        assertEquals(QuickMessage.MAX_REPEATS, q.repeats);
        assertEquals(QuickMessage.MIN_GAP_SECONDS, q.gapSeconds);
        q.repeats = 0;
        q.gapSeconds = 100000;
        q.clamp();
        assertEquals(1, q.repeats);
        assertEquals(QuickMessage.MAX_GAP_SECONDS, q.gapSeconds);
    }

    @Test
    public void locationGoesOnItsOwnLineAndCountsTowardsTheSize() {
        String loc = "📍[geo:40.712800,-74.006000]";
        QuickMessage q = new QuickMessage("Help", "Need help", true);
        assertEquals("Need help\n" + loc, q.compose(loc));
        assertTrue("room is kept for the longest location", q.bytes() >= q.compose(loc).getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals("location only", loc, new QuickMessage("Me", "", true).compose(loc));
        assertEquals("no location asked for", "OK", new QuickMessage("OK", " OK ", false).compose(loc));
        assertEquals("location left out when not available", "Need help", q.compose(null));
    }

    @Test
    public void titleFallsBackToTheText() {
        assertEquals("OK", new QuickMessage("OK", "Okay then", false).title());
        assertEquals("Okay then", new QuickMessage(" ", "Okay then", false).title());
        String longText = "This is a rather long quick message text";
        assertEquals(QuickMessage.MAX_LABEL_LENGTH, new QuickMessage("", longText, false).title().length());
    }

    @Test
    public void emptyMessagesAreDropped() {
        assertFalse(new QuickMessage("x", "  ", false).hasContent());
        assertTrue(new QuickMessage("x", "", true).hasContent());
        assertTrue(QuickMessage.fromJson("[{\"label\":\"x\",\"text\":\"\"}]").isEmpty());
    }
}
