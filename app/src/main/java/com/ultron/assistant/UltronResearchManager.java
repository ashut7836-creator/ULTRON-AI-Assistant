package com.ultron.assistant;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Enqueues and manages user-requested background research.
 *
 * WorkManager waits for network connectivity and may defer execution according
 * to Android battery and background restrictions. It is not an exact-time service.
 */
public final class UltronResearchManager {

    public static final String TAG_RESEARCH = "ultron_background_research";
    public static final String KEY_TASK_ID = "research_task_id";
    public static final String KEY_TOPIC = "research_topic";

    private UltronResearchManager() {}

    public static UUID scheduleResearch(Context context, String topic) {
        if (context == null || topic == null) {
            throw new IllegalArgumentException("Research needs an application context and a topic.");
        }

        String cleanTopic = topic.trim();
        if (cleanTopic.isEmpty() || cleanTopic.length() > 300) {
            throw new IllegalArgumentException("Research topic must be 1 to 300 characters.");
        }

        Context appContext = context.getApplicationContext();
        UUID id = UUID.randomUUID();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(
                UltronResearchWorker.class)
                .setId(id)
                .setInputData(new androidx.work.Data.Builder()
                        .putString(KEY_TASK_ID, id.toString())
                        .putString(KEY_TOPIC, cleanTopic)
                        .build())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG_RESEARCH)
                .build();

        UltronResearchStore store = new UltronResearchStore(appContext);
        try {
            store.createTask(id.toString(), cleanTopic);
        } finally {
            store.close();
        }

        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                    "ultron-research-" + id,
                    ExistingWorkPolicy.KEEP,
                    keyedRequest);
        } catch (RuntimeException error) {
            UltronResearchStore cleanup = new UltronResearchStore(appContext);
            try {
                cleanup.markFailed(id.toString(), "Android couldn't place this task in the background queue.");
            } finally {
                cleanup.close();
            }
            throw error;
        }

        return id;
    }

    public static List<UltronResearchStore.Task> listTasks(Context context)
            throws Exception {
        UltronResearchStore store = new UltronResearchStore(context.getApplicationContext());
        try {
            return store.listTasks(20);
        } finally {
            store.close();
        }
    }

    public static UltronResearchStore.Task getTask(Context context, UUID id) {
        if (context == null || id == null) return null;
        UltronResearchStore store = new UltronResearchStore(context.getApplicationContext());
        try {
            return store.getTask(id.toString());
        } finally {
            store.close();
        }
    }

    public static void cancelTask(Context context, UUID id) throws Exception {
        if (context == null || id == null) return;
        WorkManager.getInstance(context.getApplicationContext())
                .cancelWorkById(id)
                .getResult()
                .get(20, TimeUnit.SECONDS);

        UltronResearchStore store = new UltronResearchStore(context.getApplicationContext());
        try {
            store.markCancelled(id.toString());
        } finally {
            store.close();
        }
    }
}
