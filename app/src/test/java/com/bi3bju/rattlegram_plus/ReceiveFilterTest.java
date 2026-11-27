package com.bi3bju.rattlegram_plus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class ReceiveFilterTest {
    private static final List<String> CONTACTS = Arrays.asList("KE2CZZ1", "N0CALL");
    private static final String ME = "KE2CZZ";
    private static final String CIPHER = "q3Yk9M2wXr0bLzT1cV8pN4hJ6sE7uA5oG2iD0fK9mB4xZw==";

    private static ReceiveFilter.Verdict plain(ReceiveFilter f, String call, String text, long now) {
        return f.check(call, call, false, false, text, CONTACTS, ME, now);
    }

    @Test
    public void everythingIsShownWhileAllFiltersAreOff() {
        ReceiveFilter f = new ReceiveFilter();
        assertFalse(f.anyEnabled());
        assertEquals(ReceiveFilter.Verdict.SHOW, plain(f, "W1AW", "hello", 0));
        assertEquals(ReceiveFilter.Verdict.SHOW, plain(f, "W1AW", "hello", 1));
        assertEquals(ReceiveFilter.Verdict.SHOW, plain(f, ME, "hello", 2));
        assertEquals(ReceiveFilter.Verdict.SHOW, f.check("W1AW", "W1AW", false, false, null, CONTACTS, ME, 3));
    }

    @Test
    public void repeatsWithinTheWindowAreIgnored() {
        ReceiveFilter f = new ReceiveFilter();
        f.ignoreDuplicates = true;
        assertEquals(ReceiveFilter.Verdict.SHOW, plain(f, "W1AW", "hello", 0));
        assertEquals(ReceiveFilter.Verdict.DUPLICATE, plain(f, "W1AW", "hello", 60_000));
        assertEquals("different text", ReceiveFilter.Verdict.SHOW, plain(f, "W1AW", "hello again", 60_000));
        assertEquals("different station", ReceiveFilter.Verdict.SHOW, plain(f, "N0CALL", "hello", 60_000));
        assertEquals("after the window", ReceiveFilter.Verdict.SHOW,
                plain(f, "W1AW", "hello", ReceiveFilter.DUPLICATE_WINDOW_MS + 1));
    }

    @Test
    public void pingsAreNeverRepeats() {
        ReceiveFilter f = new ReceiveFilter();
        f.ignoreDuplicates = true;
        assertEquals(ReceiveFilter.Verdict.SHOW, f.check("W1AW", "W1AW", false, false, null, CONTACTS, ME, 0));
        assertEquals(ReceiveFilter.Verdict.SHOW, f.check("W1AW", "W1AW", false, false, null, CONTACTS, ME, 1));
    }

    @Test
    public void unencryptedMessagesAndPingsAreIgnoredOnRequest() {
        ReceiveFilter f = new ReceiveFilter();
        f.ignoreUnencrypted = true;
        assertEquals(ReceiveFilter.Verdict.UNENCRYPTED, plain(f, "W1AW", "hello", 0));
        assertEquals(ReceiveFilter.Verdict.UNENCRYPTED, f.check("W1AW", "W1AW", false, false, null, CONTACTS, ME, 0));
        assertEquals(ReceiveFilter.Verdict.SHOW, f.check("W1AW", "W1AW", true, true, CIPHER, CONTACTS, ME, 0));
        assertEquals("kept for unlocking later", ReceiveFilter.Verdict.SHOW,
                f.check("W1AW", "W1AW", true, false, CIPHER, CONTACTS, ME, 0));
    }

    @Test
    public void contactsOnlyUsesTheHiddenCallSignOnceDecrypted() {
        ReceiveFilter f = new ReceiveFilter();
        f.contactsOnly = true;
        assertEquals(ReceiveFilter.Verdict.NOT_A_CONTACT, plain(f, "W1AW", "hello", 0));
        assertEquals(ReceiveFilter.Verdict.SHOW, plain(f, "N0CALL", "hello", 0));
        assertEquals("case does not matter", ReceiveFilter.Verdict.SHOW, plain(f, "ke2czz1", "hello", 0));
        assertEquals(ReceiveFilter.Verdict.SHOW, f.check("ANONYMOUS", "KE2CZZ1", true, true, CIPHER, CONTACTS, ME, 0));
        assertEquals(ReceiveFilter.Verdict.NOT_A_CONTACT, f.check("ANONYMOUS", "W1AW", true, true, CIPHER, CONTACTS, ME, 0));
        assertEquals("hidden sender we cannot read", ReceiveFilter.Verdict.NOT_A_CONTACT,
                f.check("ANONYMOUS", "ANONYMOUS", true, false, CIPHER, CONTACTS, ME, 0));
    }

    @Test
    public void aSenderKnownOnlyLaterIsCheckedLater() {
        ReceiveFilter f = new ReceiveFilter();
        f.contactsOnly = true;
        f.ignoreOwnCall = true;
        assertEquals(ReceiveFilter.Verdict.SHOW, f.check("ANONYMOUS", null, true, false, "~P part", CONTACTS, ME, 0));
        f.ignoreUnencrypted = true;
        assertEquals(ReceiveFilter.Verdict.UNENCRYPTED, f.check("ANONYMOUS", null, false, false, "~P part", CONTACTS, ME, 0));
    }

    @Test
    public void ownCallSignIsIgnoredOnRequest() {
        ReceiveFilter f = new ReceiveFilter();
        f.ignoreOwnCall = true;
        assertEquals(ReceiveFilter.Verdict.OWN_CALL, plain(f, "KE2CZZ", "hello", 0));
        assertEquals(ReceiveFilter.Verdict.OWN_CALL, f.check("ANONYMOUS", "KE2CZZ", true, true, CIPHER, CONTACTS, ME, 0));
        assertEquals("a longer call sign is someone else", ReceiveFilter.Verdict.SHOW, plain(f, "KE2CZZ1", "hello", 0));
        assertEquals(ReceiveFilter.Verdict.SHOW, f.check("ANONYMOUS", "ANONYMOUS", true, false, CIPHER, CONTACTS, ME, 0));
        assertEquals("no own call sign set", ReceiveFilter.Verdict.SHOW,
                f.check("ANONYMOUS", "ANONYMOUS", false, false, "x", Collections.emptyList(), "", 0));
    }

    @Test
    public void ciphertextShapeIsRecognised() {
        assertTrue(ReceiveFilter.looksEncrypted(CIPHER));
        assertTrue(ReceiveFilter.looksEncrypted("  " + CIPHER + " "));
        assertFalse(ReceiveFilter.looksEncrypted("hello"));
        assertFalse(ReceiveFilter.looksEncrypted("CQ CQ DE KE2CZZ"));
        assertFalse("too short to hold nonce and tag", ReceiveFilter.looksEncrypted("QUJDREVGR0hJSktMTU5PUFFSU1RVVldY"));
        assertFalse(ReceiveFilter.looksEncrypted(null));
    }
}
