package com.bi3bju.rattlegram_plus;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.io.IOException;

/**
 * The USB serial adapter lent to Hamlib (see rig-hamlib.c). Android apps cannot open /dev/ttyUSB*, so the
 * patched Hamlib calls these static methods for its "android-usb:<deviceId>:<portIndex>" serial path: read and
 * write from Hamlib's two bridge threads, open and close from the rig thread.
 * <p>
 * Return values follow hamlib/rpt_usb_serial.h: >= 0 success (bytes moved, 0 = read timed out), < 0 failure,
 * {@link #EGONE} once the adapter was unplugged.
 */
final class RigUsbSerial {
    static final int EGONE = -2;
    static final int EFAIL = -1;

    private static volatile UsbManager usbManager;
    private static volatile UsbSerialPort port;
    private static volatile boolean gone;
    private static final Object lock = new Object();

    private RigUsbSerial() {}

    static void setUsbManager(UsbManager manager) {
        usbManager = manager;
    }

    /** The adapter was unplugged: fail every further read and write at once. */
    static void markGone() {
        gone = true;
    }

    // ---- called from native code ----

    static int isReady() {
        return usbManager != null ? 1 : 0;
    }

    static int open(int deviceId, int portIndex, int baudRate, int dataBits, int stopBits, int parity) {
        synchronized (lock) {
            UsbManager manager = usbManager;
            if (port != null || manager == null)
                return EFAIL;
            UsbDevice device = null;
            for (UsbDevice d : manager.getDeviceList().values())
                if (d.getDeviceId() == deviceId)
                    device = d;
            if (device == null)
                return EGONE;
            if (!manager.hasPermission(device))
                return EFAIL;
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
            if (driver == null || portIndex < 0 || portIndex >= driver.getPorts().size())
                return EFAIL;
            UsbDeviceConnection connection = manager.openDevice(device);
            if (connection == null)
                return EFAIL;
            UsbSerialPort p = driver.getPorts().get(portIndex);
            try {
                p.open(connection);
                // Hamlib's serial_parity_e uses the same order (none, odd, even, mark, space)
                p.setParameters(baudRate, dataBits,
                        stopBits == 2 ? UsbSerialPort.STOPBITS_2 : UsbSerialPort.STOPBITS_1, parity);
                // Some adapters raise DTR/RTS on open; on interfaces like the Digirig, RTS keys the radio
                p.setDTR(false);
                p.setRTS(false);
            } catch (Exception e) {
                try {
                    p.close();
                } catch (IOException ignored) {
                }
                return EFAIL;
            }
            gone = false;
            port = p;
            return 0;
        }
    }

    static int read(byte[] buffer, int length, int timeoutMs) {
        UsbSerialPort p = port;
        if (p == null || gone)
            return EGONE;
        // Straight into Hamlib's buffer: its bridge reads 512 bytes at a time, more than a USB packet, so no
        // bytes are lost and nothing needs buffering (or locking) here
        try {
            // A timeout of 0 means "wait forever" to the USB library; never block Hamlib like that
            int n = p.read(buffer, Math.max(timeoutMs, 20));
            return Math.max(n, 0);
        } catch (IOException e) {
            return gone || !p.isOpen() ? EGONE : EFAIL;
        }
    }

    static int write(byte[] buffer, int length, int timeoutMs) {
        UsbSerialPort p = port;
        if (p == null || gone)
            return EGONE;
        try {
            p.write(buffer, Math.max(timeoutMs, 500));
            return length;
        } catch (IOException e) {
            return gone || !p.isOpen() ? EGONE : EFAIL;
        }
    }

    static int setRts(int state) {
        UsbSerialPort p = port;
        if (p == null)
            return EGONE;
        try {
            p.setRTS(state != 0);
            return 0;
        } catch (UnsupportedOperationException e) {
            return 0;
        } catch (IOException e) {
            return gone ? EGONE : EFAIL;
        }
    }

    static int setDtr(int state) {
        UsbSerialPort p = port;
        if (p == null)
            return EGONE;
        try {
            p.setDTR(state != 0);
            return 0;
        } catch (UnsupportedOperationException e) {
            return 0;
        } catch (IOException e) {
            return gone ? EGONE : EFAIL;
        }
    }

    static int flush() {
        UsbSerialPort p = port;
        if (p == null)
            return EGONE;
        try {
            p.purgeHwBuffers(true, true);
            return 0;
        } catch (UnsupportedOperationException e) {
            return 0; // not every chip can purge
        } catch (IOException e) {
            return gone ? EGONE : EFAIL;
        }
    }

    static int close() {
        synchronized (lock) {
            UsbSerialPort p = port;
            port = null;
            if (p != null) {
                try {
                    p.close();
                } catch (IOException ignored) {
                }
            }
            return 0;
        }
    }
}
