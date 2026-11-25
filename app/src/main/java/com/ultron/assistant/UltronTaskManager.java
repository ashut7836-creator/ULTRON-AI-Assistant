package com.ultron.assistant;

import android.content.Context;

import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Persistent task queue for user-created ULTRON reminders.
 *
 * Reminder work is deferrable and managed by Android. It is not an exact-alarm
 * API and does not promise execution at the exact wall-clock second.
 */
public final class UltronTaskManager {

    public static final String TAG_REMINDERS = "ultron_user_reminder";
    public static final String KEY_REMINDER_TEXT = "reminder_text";
    public static final String KEY_DUE_AT = "due_at_epoch_ms";

    private UltronTaskManager() {}

    public static UUID scheduleReminder(
            Context context, String reminderText, long delayMillis) {
        if (context == null || reminderText == null) {
            throw new IllegalArgumentException("A reminder needs text and an application context.");
        }

        String cleanText = reminderText.trim();
        if (cleanText.isEmpty() || cleanText.length() > 240) {
            throw new IllegalArgumentException("Reminder text must be 1 to 240 characters.");
        }
        if (delayMillis < 60_000L || delayMillis > TimeUnit.DAYS.toMillis(30)) {
            throw new IllegalArgumentException("Reminder delay must be between 1 minute and 30 days.");
        }

        long dueAt = System.currentTimeMillis() + delayMillis;
        Data input = new Data.Builder()
                .putString(KEY_REMINDER_TEXT, cleanText)
                .putLong(KEY_DUE_AT, dueAt)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                UltronReminderWorker.class)
                .setInputData(input)
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .addTag(TAG_REMINDERS)
                .build();

        WorkManager.getInstance(context.getApplicationContext()).enqueueUniqueWork(
                "ultron-reminder-" + request.getId(),
                ExistingWorkPolicy.KEEP,
                request);

        return request.getId();
    }

    public static List<WorkInfo> getReminders(Context context)
            throws Exception {
        return WorkManager.getInstance(context.getApplicationContext())
                .getWorkInfosByTag(TAG_REMINDERS)
                .get(20, TimeUnit.SECONDS);
    }

    public static void cancelReminder(Context context, UUID id) {
        if (context == null || id == null) return;
        WorkManager.getInstance(context.getApplicationContext()).cancelWorkById(id);
    }
}
