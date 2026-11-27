package com.bi3bju.rattlegram_plus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A quick message: a button that transmits a saved text with one tap (the ⚡ button). Every part of it
 * can be edited. Pure Java (plus org.json) so it can be tested off the phone.
 */
final class QuickMessage {
    static final int MAX_REPEATS = 5;
    static final int MIN_GAP_SECONDS = 5, MAX_GAP_SECONDS = 600, DEFAULT_GAP_SECONDS = 20;
    static final int MAX_LABEL_LENGTH = 24;
    /** Room a location takes: a newline plus 📍[geo:-90.123456,-180.123456] at its longest. */
    static final int LOCATION_BYTES = 1 + "📍[geo:-90.123456,-180.123456]".getBytes(StandardCharsets.UTF_8).length;

    String label = "";
    String text = "";
    boolean encrypt;
    boolean addLocation;
    /** How many times it is transmitted in all (1 = once). */
    int repeats = 1;
    int gapSeconds = DEFAULT_GAP_SECONDS;
    boolean confirm = true;

    QuickMessage() {
    }

    QuickMessage(String label, String text, boolean addLocation) {
        this.label = label;
        this.text = text;
        this.addLocation = addLocation;
    }

    /** What is shown on its button: the label, or the start of the text when there is no label. */
    String title() {
        String l = label.trim();
        if (!l.isEmpty())
            return l;
        String t = text.trim().replace('\n', ' ');
        return t.length() > MAX_LABEL_LENGTH ? t.substring(0, MAX_LABEL_LENGTH - 1) + "…" : t;
    }

    /** Bytes it takes in a message, counting the room for a location if one is added. */
    int bytes() {
        return text.getBytes(StandardCharsets.UTF_8).length + (addLocation ? LOCATION_BYTES : 0);
    }

    /** Whether there is something to send: text, a location, or both. */
    boolean hasContent() {
        return !text.trim().isEmpty() || addLocation;
    }

    /** The text as transmitted, with the location (already formatted, or null) on its own line. */
    String compose(String location) {
        String t = text.trim();
        if (!addLocation || location == null)
            return t;
        return t.isEmpty() ? location : t + "\n" + location;
    }

    QuickMessage copy() {
        QuickMessage q = new QuickMessage();
        q.label = label;
        q.text = text;
        q.encrypt = encrypt;
        q.addLocation = addLocation;
        q.repeats = repeats;
        q.gapSeconds = gapSeconds;
        q.confirm = confirm;
        return q;
    }

    /** Keeps the numbers in range, for values typed in or read from older settings. */
    void clamp() {
        repeats = Math.max(1, Math.min(MAX_REPEATS, repeats));
        gapSeconds = Math.max(MIN_GAP_SECONDS, Math.min(MAX_GAP_SECONDS, gapSeconds));
        if (label.length() > MAX_LABEL_LENGTH)
            label = label.substring(0, MAX_LABEL_LENGTH);
    }

    /** Starter set shown until the user changes the list; all of it can be edited or deleted. */
    static List<QuickMessage> defaults() {
        List<QuickMessage> list = new ArrayList<>();
        list.add(new QuickMessage("OK", "OK", false));
        list.add(new QuickMessage("Copy", "Copy, thanks", false));
        list.add(new QuickMessage("On my way", "On my way", false));
        list.add(new QuickMessage("My location", "", true));
        list.add(new QuickMessage("Need help", "Need help", true));
        return list;
    }

    static String toJson(List<QuickMessage> list) {
        JSONArray arr = new JSONArray();
        try {
            for (QuickMessage q : list) {
                JSONObject o = new JSONObject();
                o.put("label", q.label);
                o.put("text", q.text);
                o.put("encrypt", q.encrypt);
                o.put("location", q.addLocation);
                o.put("repeats", q.repeats);
                o.put("gap", q.gapSeconds);
                o.put("confirm", q.confirm);
                arr.put(o);
            }
        } catch (JSONException e) {
            return "[]";
        }
        return arr.toString();
    }

    /** The saved list; the starter set when nothing was saved yet, an empty list when it cannot be read. */
    static List<QuickMessage> fromJson(String json) {
        if (json == null)
            return defaults();
        List<QuickMessage> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); ++i) {
                JSONObject o = arr.getJSONObject(i);
                QuickMessage q = new QuickMessage();
                q.label = o.optString("label", "");
                q.text = o.optString("text", "");
                q.encrypt = o.optBoolean("encrypt", false);
                q.addLocation = o.optBoolean("location", false);
                q.repeats = o.optInt("repeats", 1);
                q.gapSeconds = o.optInt("gap", DEFAULT_GAP_SECONDS);
                q.confirm = o.optBoolean("confirm", true);
                q.clamp();
                if (q.hasContent())
                    list.add(q);
            }
        } catch (JSONException e) {
            return new ArrayList<>();
        }
        return list;
    }
}
