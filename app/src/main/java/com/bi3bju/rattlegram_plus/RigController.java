package com.bi3bju.rattlegram_plus;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;

import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional Hamlib rig control: one radio on a USB serial (CAT) adapter. Connects only when the user asks,
 * asks for USB permission only then, never reconnects or keys on its own, and runs every Hamlib call on one
 * rig thread so the screen never waits for the radio.
 */
final class RigController {
    enum State {DISCONNECTED, CONNECTING, CONNECTED}

    interface Listener {
        /** State changed; message explains a failure or what is happening, or is null. */
        void onRigState(State state, String message);

        void onPttProblem(String message);
    }

    interface Result<T> {
        void done(T value, String error);
    }

    private final Context context;
    private final UsbManager usbManager;
    private final Handler main;
    private final ExecutorService rigThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "rig"));
    private final RigBackend backend;
    private final PttCoordinator ptt;
    private final Listener listener;
    private final String permissionAction;

    private State state = State.DISCONNECTED;
    private RigSettings settings;
    private UsbDevice device;
    private boolean receiverRegistered;
    private double lastFrequency = -1;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (permissionAction.equals(intent.getAction())) {
                if (state != State.CONNECTING || device == null || d == null || d.getDeviceId() != device.getDeviceId())
                    return;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    openRig();
                else
                    fail(context.getString(R.string.rig_permission_denied));
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (device != null && d != null && d.getDeviceId() == device.getDeviceId()) {
                    RigUsbSerial.markGone();
                    fail(context.getString(R.string.rig_unplugged));
                }
            }
        }
    };

    RigController(Context context, Handler main, Listener listener) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        this.main = main;
        this.listener = listener;
        this.permissionAction = context.getPackageName() + ".RIG_USB_PERMISSION";
        this.backend = new HamlibRig();
        RigUsbSerial.setUsbManager(usbManager);
        this.ptt = new PttCoordinator(backend, rigThread, new PttCoordinator.Scheduler() {
            @Override
            public Object postDelayed(Runnable task, long delayMs) {
                main.postDelayed(task, delayMs);
                return task;
            }

            @Override
            public void cancel(Object token) {
                main.removeCallbacks((Runnable) token);
            }
        }, listener::onPttProblem);
    }

    State state() {
        return state;
    }

    boolean isConnected() {
        return state == State.CONNECTED;
    }

    PttCoordinator ptt() {
        return ptt;
    }

    /** Last frequency read from the radio in Hz, or -1. */
    double lastFrequency() {
        return lastFrequency;
    }

    static String deviceKey(UsbDevice d) {
        return String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId());
    }

    /** USB serial adapters plugged in now (no permission needed to list them). */
    List<UsbSerialDriver> adapters() {
        return UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
    }

    void connect(RigSettings s) {
        if (state != State.DISCONNECTED)
            return;
        settings = s;
        device = null;
        for (UsbSerialDriver driver : adapters())
            if (device == null && (s.device.isEmpty() || deviceKey(driver.getDevice()).equals(s.device)))
                device = driver.getDevice();
        if (device == null) {
            fail(context.getString(R.string.rig_no_adapter));
            return;
        }
        registerReceiver();
        setState(State.CONNECTING, null);
        if (usbManager.hasPermission(device)) {
            openRig();
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            Intent intent = new Intent(permissionAction).setPackage(context.getPackageName());
            usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags));
            setState(State.CONNECTING, context.getString(R.string.rig_waiting_permission));
        }
    }

    private void openRig() {
        final RigSettings s = settings;
        final String path = "android-usb:" + device.getDeviceId() + ":0";
        rigThread.execute(() -> {
            String error = null;
            double hz = -1;
            try {
                backend.open(s, path);
                HamlibRig.ModelInfo info = HamlibRig.modelInfo(s.model);
                if (info != null && info.canGetFrequency) {
                    // Reading the frequency also proves the radio answers; nothing is ever set on connect
                    hz = backend.getFrequency();
                }
            } catch (RigBackend.RigException e) {
                backend.close();
                error = e.getMessage();
            }
            final String failure = error;
            final double frequency = hz;
            main.post(() -> {
                if (state != State.CONNECTING)
                    return; // disconnected meanwhile
                if (failure != null) {
                    fail(context.getString(R.string.rig_open_failed, failure));
                } else {
                    lastFrequency = frequency;
                    setState(State.CONNECTED, null);
                }
            });
        });
    }

    /** Unkeys the radio if needed and closes the connection. */
    void disconnect() {
        ptt.releaseNow();
        rigThread.execute(() -> {
            backend.close();
            RigUsbSerial.close();
        });
        unregisterReceiver();
        device = null;
        lastFrequency = -1;
        setState(State.DISCONNECTED, null);
    }

    /** For the app closing: disconnect, and let the rig thread finish the "PTT off" and close. */
    void shutdown() {
        if (state != State.DISCONNECTED)
            disconnect();
        rigThread.shutdown();
    }

    /** {@link #shutdown()}, waiting up to timeoutMs for the radio to be unkeyed and closed. */
    void shutdownAndWait(long timeoutMs) {
        shutdown();
        try {
            rigThread.awaitTermination(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    void readFrequency(Result<Double> result) {
        if (!isConnected()) {
            result.done(null, context.getString(R.string.rig_not_connected));
            return;
        }
        rigThread.execute(() -> {
            try {
                double hz = backend.getFrequency();
                main.post(() -> {
                    lastFrequency = hz;
                    result.done(hz, null);
                });
            } catch (RigBackend.RigException e) {
                main.post(() -> result.done(null, e.getMessage()));
            }
        });
    }

    /** Tunes the radio; only ever called when the user asks for it. */
    void setFrequency(double hz, Result<Double> result) {
        if (!isConnected()) {
            result.done(null, context.getString(R.string.rig_not_connected));
            return;
        }
        rigThread.execute(() -> {
            try {
                backend.setFrequency(hz);
                double now = backend.getFrequency();
                main.post(() -> {
                    lastFrequency = now;
                    result.done(now, null);
                });
            } catch (RigBackend.RigException e) {
                main.post(() -> result.done(null, e.getMessage()));
            }
        });
    }

    private void fail(String message) {
        boolean wasOpen = state != State.DISCONNECTED;
        ptt.releaseNow();
        if (wasOpen)
            rigThread.execute(() -> {
                backend.close();
                RigUsbSerial.close();
            });
        unregisterReceiver();
        device = null;
        lastFrequency = -1;
        setState(State.DISCONNECTED, message);
    }

    private void setState(State newState, String message) {
        state = newState;
        listener.onRigState(newState, message);
    }

    private void registerReceiver() {
        if (receiverRegistered)
            return;
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered)
            return;
        try {
            context.unregisterReceiver(usbReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        receiverRegistered = false;
    }
}
