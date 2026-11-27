package com.bi3bju.rattlegram_plus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Optional background listening. While it runs, Android lets the app keep using the microphone with the
 * screen off or another app in front, and shows an ongoing notification saying so. The audio itself is
 * still read and decoded by MainActivity; this service only keeps the app allowed (and awake) to do it.
 *
 * <p>Started only while the app is on screen (Android 14 and later refuse to start microphone access from
 * the background), stopped when it is turned off, the notification's Stop is tapped, or the app closes.
 */
public class ListenService extends Service {
    static final String CHANNEL_ID = "background";
    private static final int NOTIFICATION_ID = 1;
    private static final String ACTION_STOP = "com.bi3bju.rattlegram_plus.STOP_LISTENING";

    /** Called on the main thread when the notification's Stop is tapped; set by MainActivity. */
    static Runnable onStopRequested;

    private PowerManager.WakeLock wakeLock;

    static void start(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ListenService.class));
        } catch (RuntimeException e) {
            // Refused (for example started from the background); MainActivity tries again when on screen
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ListenService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Runnable r = onStopRequested;
            if (r != null)
                r.run();
            stopSelf();
            return START_NOT_STICKY;
        }
        createChannel();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0;
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type);
        } catch (RuntimeException e) {
            // No microphone permission, or not allowed right now: do not run half started
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rattlegram:listen");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        }
        // Never restarted by Android on its own after the app was closed: listening needs the app
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Swiped away from Recent apps: stop listening along with the app
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld())
            wakeLock.release();
        wakeLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.background_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.background_channel_description));
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null)
            nm.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, ListenService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_wifi_tethering_24)
                .setContentTitle(getString(R.string.background_notification_title))
                .setContentText(getString(R.string.background_notification_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.background_stop), stopPi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }
}
