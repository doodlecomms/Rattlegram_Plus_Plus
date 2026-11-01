package com.bi3bju.rattlegram_plus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link RigBackend} on Hamlib (libhamlib.so, LGPL, see hamlib/README.md) through librattlegram-rig.so.
 * The libraries are only loaded by {@link #load()}, which the app calls once the user turns Hamlib on.
 */
final class HamlibRig implements RigBackend {
    /** One Hamlib rig model, for the model picker. */
    static final class Model {
        final int id;
        final String manufacturer, name;

        Model(int id, String manufacturer, String name) {
            this.id = id;
            this.manufacturer = manufacturer;
            this.name = name;
        }

        @Override
        public String toString() {
            return manufacturer + " " + name + " (" + id + ")";
        }
    }

    /** Serial settings and supported operations of a model, from Hamlib's capabilities. */
    static final class ModelInfo {
        final int rateMin, rateMax, dataBits, stopBits, parity;
        final boolean serial, canGetFrequency, canSetFrequency, canSetPtt, canGetPtt;

        ModelInfo(int[] info) {
            rateMin = info[0];
            rateMax = info[1];
            dataBits = info[2];
            stopBits = info[3];
            parity = info[4];
            serial = info[5] == 1; // RIG_PORT_SERIAL
            canGetFrequency = info[6] != 0;
            canSetFrequency = info[7] != 0;
            canSetPtt = info[8] != 0;
            canGetPtt = info.length > 9 && info[9] != 0;
        }
    }

    private static Boolean loaded;
    private static String loadError;

    private static final int PTT_CHECKS = 5;
    private static final long PTT_CHECK_INTERVAL_MS = 50;

    private long handle;
    /** The open model can report whether it is transmitting, so every PTT change is checked. */
    private boolean verifyPtt;

    /** Loads Hamlib and the bridge once; false (see {@link #loadError()}) if this phone cannot run them. */
    static synchronized boolean load() {
        if (loaded == null) {
            try {
                System.loadLibrary("hamlib");
                System.loadLibrary("rattlegram-rig");
                loaded = true;
            } catch (Throwable e) {
                // e.g. a 32-bit x86 phone, which has no Hamlib build
                loadError = e.getMessage();
                loaded = false;
            }
        }
        return loaded;
    }

    static synchronized String loadError() {
        return loadError;
    }

    static String version() {
        return nativeVersion();
    }

    /** All serial-port rig models Hamlib knows, sorted by manufacturer and name. */
    static List<Model> models() {
        List<String> lines = new ArrayList<>();
        nativeListModels(lines);
        List<Model> models = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split("\t", 3);
            if (f.length < 3)
                continue;
            int id = Integer.parseInt(f[0]);
            int[] info = nativeModelInfo(id);
            if (info != null && new ModelInfo(info).serial)
                models.add(new Model(id, f[1].trim(), f[2].trim()));
        }
        Collections.sort(models, (a, b) -> {
            int c = a.manufacturer.compareToIgnoreCase(b.manufacturer);
            return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
        });
        return models;
    }

    static ModelInfo modelInfo(int model) {
        int[] info = nativeModelInfo(model);
        return info == null ? null : new ModelInfo(info);
    }

    @Override
    public void open(RigSettings settings, String serialPath) throws RigException {
        close();
        ModelInfo info = modelInfo(settings.model);
        if (info == null)
            throw new RigException("Hamlib does not know rig model " + settings.model);
        String[] parities = {"None", "Odd", "Even", "Mark", "Space"};
        String parity = info.parity >= 0 && info.parity < parities.length ? parities[info.parity] : "None";
        long[] h = new long[1];
        int rc = nativeOpen(settings.model, serialPath, settings.baudRate, info.dataBits, info.stopBits, parity, h);
        if (rc != 0 || h[0] == 0)
            throw new RigException(nativeErrorText(rc));
        handle = h[0];
        verifyPtt = info.canGetPtt;
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeClose(handle);
            handle = 0;
        }
    }

    @Override
    public boolean isOpen() {
        return handle != 0;
    }

    @Override
    public double getFrequency() throws RigException {
        check();
        double hz = nativeGetFreq(handle);
        if (hz < 0)
            throw new RigException(nativeErrorText((int) hz));
        return hz;
    }

    @Override
    public void setFrequency(double hz) throws RigException {
        check();
        int rc = nativeSetFreq(handle, hz);
        if (rc != 0)
            throw new RigException(nativeErrorText(rc));
    }

    /**
     * Sets PTT and, where the radio can report it, reads it back: some radios (the FT-857 among them) give no
     * reliable acknowledgement, so "OK" alone does not prove the radio keyed or unkeyed.
     */
    @Override
    public void setPtt(boolean on) throws RigException {
        check();
        int rc = nativeSetPtt(handle, on);
        if (rc != 0)
            throw new RigException(nativeErrorText(rc));
        if (!verifyPtt)
            return;
        // A radio may take a few tens of milliseconds to switch over
        for (int attempt = 0; ; ++attempt) {
            int state = nativeGetPtt(handle);
            if (state < 0) {
                // The model can report PTT but this radio did not answer: do not trust the command either
                throw new RigException(nativeErrorText(state));
            }
            if ((state != 0) == on)
                return;
            if (attempt == PTT_CHECKS - 1)
                throw new RigException(on ? "the radio did not start transmitting" : "the radio is still transmitting");
            try {
                Thread.sleep(PTT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RigException("interrupted");
            }
        }
    }

    private void check() throws RigException {
        if (handle == 0)
            throw new RigException("Radio not connected");
    }

    private static native void nativeListModels(List<String> out);

    private static native int[] nativeModelInfo(int model);

    private static native int nativeOpen(int model, String path, int baudRate, int dataBits, int stopBits, String parity,
                                         long[] handleOut);

    private static native void nativeClose(long handle);

    private static native double nativeGetFreq(long handle);

    private static native int nativeSetFreq(long handle, double hz);

    private static native int nativeSetPtt(long handle, boolean on);

    private static native int nativeGetPtt(long handle);

    private static native String nativeErrorText(int code);

    private static native String nativeVersion();
}
