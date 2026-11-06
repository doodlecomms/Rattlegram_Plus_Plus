package com.bi3bju.rattlegram_plus;

/**
 * The few rig-control operations Rattlegram needs. Implemented by Hamlib ({@link HamlibRig}) and by a fake in
 * the unit tests. Every method may block on serial I/O, so callers run them on the rig thread only.
 */
interface RigBackend {
    final class RigException extends Exception {
        RigException(String message) {
            super(message);
        }
    }

    void open(RigSettings settings, String serialPath) throws RigException;

    void close();

    boolean isOpen();

    /** Current frequency in Hz. */
    double getFrequency() throws RigException;

    void setFrequency(double hz) throws RigException;

    void setPtt(boolean on) throws RigException;

    /** {mode, filter width} as Hamlib reports them (mode is a RigModes value). */
    long[] getMode() throws RigException;

    /** Sets the mode; a negative width keeps the current filter. */
    void setMode(long mode, long width) throws RigException;
}
