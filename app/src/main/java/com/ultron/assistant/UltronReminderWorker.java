package com.ultron.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Delivers a scheduled ULTRON reminder as a normal Android notification.
 * WorkManager owns the scheduling lifecycle; this worker does not keep a
 * service running or continuously execute in the background.
 */
public final class UltronReminderWorker extends Worker {

    private static final String CHANNEL_ID = "ultron_reminders";
    private static final int CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT;

    public UltronReminderWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        String reminder = getInputData().getString(UltronTaskManager.KEY_REMINDER_TEXT);

        if (reminder == null || reminder.trim().isEmpty()) {
            UltronTaskManager.clearReminder(context, getId());
            return Result.failure();
        }

        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // The user may revoke notification permission after scheduling.
            // Do not attempt to bypass Android's permission decision.
            UltronTaskManager.clearReminder(context, getId());
            return Result.success();
        }

        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return Result.retry();

        createChannel(manager);

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context,
                getId().hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("ULTRON reminder")
                .setContentText(reminder.trim())
                .setStyle(new Notification.BigTextStyle().bigText(reminder.trim()))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setPriority(Notification.PRIORITY_DEFAULT);

        manager.notify(getId().hashCode(), notification.build());
        UltronTaskManager.clearReminder(context, getId());
        return Result.success();
    }

    private void createChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ULTRON reminders",
                    CHANNEL_IMPORTANCE);
            channel.setDescription("Notifications for reminders scheduled by the user.");
            manager.createNotificationChannel(channel);
        }
    }
}
