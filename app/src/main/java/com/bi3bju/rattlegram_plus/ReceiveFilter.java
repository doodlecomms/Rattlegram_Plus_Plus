package com.bi3bju.rattlegram_plus;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Optional receive filters (Menu → Receive Filters). Each one is off by default, so a fresh install shows
 * everything as before. Decides whether a received message is shown (and acknowledged, and relayed by the
 * repeater). Pure Java so it can be tested off the phone.
 *
 * <p>A call sign is only text the sender typed, so "contacts only" and "ignore my call sign" cut clutter but
 * do not prove who sent a message; only encryption does that.
 */
final class ReceiveFilter {
    enum Verdict { SHOW, DUPLICATE, UNENCRYPTED, NOT_A_CONTACT, OWN_CALL }

    /** How long an identical message from the same station counts as a repeat. */
    static final long DUPLICATE_WINDOW_MS = 10 * 60 * 1000;
    private static final int MAX_REMEMBERED = 200;
    /** Smallest Rattlegram Plus ciphertext: 12-byte nonce + 16-byte tag (+ at least one byte of text). */
    private static final int MIN_CIPHERTEXT_BYTES = 29;
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    boolean ignoreDuplicates;
    boolean ignoreUnencrypted;
    boolean contactsOnly;
    boolean ignoreOwnCall;

    /** Recently shown messages: key → time first seen. */
    private final Map<String, Long> recent = new LinkedHashMap<>();

    boolean anyEnabled() {
        return ignoreDuplicates || ignoreUnencrypted || contactsOnly || ignoreOwnCall;
    }

    /**
     * @param headerCall the call sign in the modem header (ANONYMOUS while the sender hides it)
     * @param realCall   the sender's call sign from inside the encryption if it was hidden and decrypted,
     *                   otherwise the same as headerCall; null when it is only known later (the parts of an
     *                   encrypted picture with a hidden call sign; the complete picture is checked again)
     * @param encrypted  whether the message is encrypted (decrypted with our password, or looks like a
     *                   ciphertext we cannot open yet)
     * @param decrypted  whether it decrypted with our password
     * @param payload    the message as received (for spotting repeats); null for pings
     * @param contacts   the call signs in Contacts
     * @param myCall     our own call sign
     */
    Verdict check(String headerCall, String realCall, boolean encrypted, boolean decrypted, String payload,
                  Collection<String> contacts, String myCall, long now) {
        if (ignoreUnencrypted && !encrypted)
            return Verdict.UNENCRYPTED;
        if (realCall != null) {
            // A hidden call sign we cannot read cannot be checked against our own or our contacts
            boolean unknownSender = !decrypted && HiddenCall.PLACEHOLDER.equals(headerCall);
            if (ignoreOwnCall && !unknownSender && sameCall(realCall, myCall))
                return Verdict.OWN_CALL;
            if (contactsOnly && (unknownSender || !isContact(realCall, contacts)))
                return Verdict.NOT_A_CONTACT;
        }
        if (ignoreDuplicates && payload != null && isRepeat(headerCall + "\n" + payload, now))
            return Verdict.DUPLICATE;
        return Verdict.SHOW;
    }

    /** Remembers the message and says whether the same one was already seen within the window. */
    private boolean isRepeat(String key, long now) {
        for (Iterator<Long> it = recent.values().iterator(); it.hasNext(); )
            if (now - it.next() > DUPLICATE_WINDOW_MS)
                it.remove();
        if (recent.containsKey(key))
            return true;
        recent.put(key, now);
        if (recent.size() > MAX_REMEMBERED)
            recent.remove(recent.keySet().iterator().next());
        return false;
    }

    void forgetRecent() {
        recent.clear();
    }

    static boolean isContact(String call, Collection<String> contacts) {
        if (call == null)
            return false;
        for (String c : contacts)
            if (sameCall(c, call))
                return true;
        return false;
    }

    static boolean sameCall(String a, String b) {
        if (a == null || b == null)
            return false;
        String x = a.trim(), y = b.trim();
        return !x.isEmpty() && x.toUpperCase(Locale.US).equals(y.toUpperCase(Locale.US));
    }

    /**
     * Whether text has the shape of a Rattlegram Plus ciphertext (Base64 of nonce + text + tag), so an
     * encrypted message we cannot open yet is not mistaken for an unencrypted one.
     */
    static boolean looksEncrypted(String text) {
        if (text == null)
            return false;
        String t = text.trim();
        if (t.length() % 4 != 0 || !BASE64.matcher(t).matches())
            return false;
        int padding = t.endsWith("==") ? 2 : t.endsWith("=") ? 1 : 0;
        return t.length() / 4 * 3 - padding >= MIN_CIPHERTEXT_BYTES;
    }
}
