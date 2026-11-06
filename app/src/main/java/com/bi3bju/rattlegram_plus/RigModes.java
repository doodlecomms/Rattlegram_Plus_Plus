package com.bi3bju.rattlegram_plus;

/**
 * The few Hamlib modes needed to put a radio in its data mode (rmode_t bits from hamlib/rig.h). Plain Java so
 * the mapping can be unit tested.
 */
final class RigModes {
    static final long AM = 1L;
    static final long CW = 1L << 1;
    static final long USB = 1L << 2;
    static final long LSB = 1L << 3;
    static final long FM = 1L << 5;
    static final long PKTLSB = 1L << 10;
    static final long PKTUSB = 1L << 11;
    static final long PKTFM = 1L << 12;

    private RigModes() {}

    static boolean isDataMode(long mode) {
        return mode == PKTFM || mode == PKTUSB || mode == PKTLSB;
    }

    /**
     * The data mode that sends the same way as mode (FM to packet FM, USB to packet USB, LSB to packet LSB), or 0
     * if there is none. On the FT-857 these are its PKT and DIG modes.
     */
    static long dataModeFor(long mode) {
        if (mode == FM)
            return PKTFM;
        if (mode == USB)
            return PKTUSB;
        if (mode == LSB)
            return PKTLSB;
        return 0;
    }

    static String name(long mode) {
        if (mode == AM) return "AM";
        if (mode == CW) return "CW";
        if (mode == USB) return "USB";
        if (mode == LSB) return "LSB";
        if (mode == FM) return "FM";
        if (mode == PKTFM) return "packet FM";
        if (mode == PKTUSB) return "packet USB";
        if (mode == PKTLSB) return "packet LSB";
        return "mode " + Long.toHexString(mode);
    }
}
