package com.bi3bju.rattlegram_plus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * Optional "new message" notifications, shown only while the app is not on screen (for example with
 * Background listening on, or a Bluetooth radio connected). Off by default.
 *
 * <p>The lock screen never shows who sent what, only "New message". With App lock on, the notification
 * itself also leaves out the sender and the text, so nothing the lock protects ends up in the shade.
 */
final class MessageNotifier {
    static final String CHANNEL_ID = "messages";
    private static final int FIRST_ID = 1000;
    private static final int MAX_SHOWN = 50;

    private final Context context;
    private int nextId = FIRST_ID;

    MessageNotifier(Context context) {
        this.context = context.getApplicationContext();
    }

    void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.notify_channel_description));
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null)
            nm.createNotificationChannel(channel);
    }

    /** Whether Android lets the app post notifications (asked for on Android 13 and later). */
    boolean allowed() {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    /**
     * @param call    the sender's call sign
     * @param text    the message as shown in the app
     * @param hideContent true to leave out sender and text (App lock is on)
     */
    void notifyMessage(String call, String text, boolean hideContent) {
        if (!allowed())
            return;
        String generic = context.getString(R.string.notify_new_message);
        String title = hideContent ? context.getString(R.string.app_name) : context.getString(R.string.notify_from, call);
        String body = hideContent ? generic : text;
        Notification publicVersion = base()
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(generic)
                .build();
        NotificationCompat.Builder b = base()
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion);
        try {
            NotificationManagerCompat.from(context).notify(nextId, b.build());
        } catch (SecurityException e) {
            return;
        }
        // Reuse a fixed range of IDs so an unattended phone never piles up more than MAX_SHOWN
        nextId = nextId + 1 >= FIRST_ID + MAX_SHOWN ? FIRST_ID : nextId + 1;
    }

    /** Removes the message notifications (when the app is opened, messages are wiped, or they are turned off). */
    void cancelAll() {
        // Only the ones actually showing: cancelling every possible ID each time the app opens runs
        // into Android's rate limit for notification updates, which then drops cancels
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null)
            return;
        for (android.service.notification.StatusBarNotification n : nm.getActiveNotifications())
            if (n.getId() >= FIRST_ID && n.getId() < FIRST_ID + MAX_SHOWN)
                nm.cancel(n.getId());
    }

    /** Android's own settings for the channel: sound, vibration, lock screen, and so on. */
    Intent channelSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            return new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID);
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.fromParts("package", context.getPackageName(), null));
    }

    private NotificationCompat.Builder base() {
        Intent open = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_baseline_chat_bubble_outline_24)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true);
    }
}
