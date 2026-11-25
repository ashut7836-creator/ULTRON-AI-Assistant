package com.ultron.assistant;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Private local storage for background research tasks and their cited sources.
 * Research topics and reports are kept on this app installation, not synced.
 */
public final class UltronResearchStore extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "ultron_research.db";
    private static final int DATABASE_VERSION = 1;

    private static final String TASKS = "research_tasks";
    private static final String SOURCES = "research_sources";

    public static final class Source {
        public final String title;
        public final String uri;

        public Source(String title, String uri) {
            this.title = title == null ? "Web source" : title;
            this.uri = uri == null ? "" : uri;
        }
    }

    public static final class Task {
        public final String id;
        public final String topic;
        public final String status;
        public final int progress;
        public final String report;
        public final String searchEntryHtml;
        public final String error;
        public final long createdAt;
        public final long updatedAt;
        public final List<Source> sources;

        Task(String id, String topic, String status, int progress,
             String report, String searchEntryHtml, String error,
             long createdAt, long updatedAt, List<Source> sources) {
            this.id = id;
            this.topic = topic;
            this.status = status;
            this.progress = progress;
            this.report = report;
            this.searchEntryHtml = searchEntryHtml;
            this.error = error;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.sources = sources == null
                    ? Collections.<Source>emptyList() : Collections.unmodifiableList(sources);
        }
    }

    public UltronResearchStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
                "CREATE TABLE " + TASKS + " (" +
                "task_id TEXT PRIMARY KEY NOT NULL, " +
                "topic TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "progress INTEGER NOT NULL DEFAULT 0, " +
                "report TEXT, " +
                "search_entry_html TEXT, " +
                "error TEXT, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL" +
                ")");
        db.execSQL(
                "CREATE TABLE " + SOURCES + " (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "task_id TEXT NOT NULL, " +
                "title TEXT NOT NULL, " +
                "uri TEXT NOT NULL" +
                ")");
        db.execSQL("CREATE INDEX idx_research_tasks_updated ON "
                + TASKS + "(updated_at)");
        db.execSQL("CREATE INDEX idx_research_sources_task ON "
                + SOURCES + "(task_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future schema migrations must preserve completed reports.
    }

    public synchronized void createTask(String id, String topic) {
        if (id == null || topic == null) {
            throw new IllegalArgumentException("Research task ID and topic are required.");
        }

        String cleanTopic = topic.trim();
        if (cleanTopic.isEmpty() || cleanTopic.length() > 300) {
            throw new IllegalArgumentException("Research topic must be 1 to 300 characters.");
        }

        long now = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("task_id", id);
        values.put("topic", cleanTopic);
        values.put("status", "QUEUED");
        values.put("progress", 0);
        values.put("created_at", now);
        values.put("updated_at", now);
        getWritableDatabase().insertOrThrow(TASKS, null, values);
    }

    public synchronized void updateProgress(String id, String status, int progress) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        if (status != null) values.put("status", status);
        values.put("progress", Math.max(0, Math.min(progress, 100)));
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                TASKS, values, "task_id = ?", new String[]{id});
    }

    public synchronized void markFailed(String id, String message) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put("status", "FAILED");
        values.put("error", safeText(message, 240));
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                TASKS, values, "task_id = ?", new String[]{id});
    }

    public synchronized void markCancelled(String id) {
        if (id == null) return;
        ContentValues values = new ContentValues();
        values.put("status", "CANCELLED");
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update(
                TASKS, values, "task_id = ?", new String[]{id});
    }

    public synchronized void saveResult(
            String id,
            String report,
            String searchEntryHtml,
            List<Source> sources,
            boolean grounded) {
        if (id == null || report == null || report.trim().isEmpty()) {
            throw new IllegalArgumentException("A research report is required.");
        }

        long now = System.currentTimeMillis();
        ContentValues taskValues = new ContentValues();
        taskValues.put("status", grounded ? "SUCCEEDED" : "UNVERIFIED");
        taskValues.put("progress", 100);
        taskValues.put("report", safeText(report, 60000));
        taskValues.put("search_entry_html", safeText(searchEntryHtml, 50000));
        taskValues.putNull("error");
        taskValues.put("updated_at", now);
        getWritableDatabase().update(
                TASKS, taskValues, "task_id = ?", new String[]{id});

        SQLiteDatabase db = getWritableDatabase();
        db.delete(SOURCES, "task_id = ?", new String[]{id});
        if (sources == null) return;

        int count = 0;
        for (Source source : sources) {
            if (source == null || count >= 30) break;
            String title = safeText(source.title, 200);
            String uri = safeUri(source.uri);
            if (title == null || uri == null) continue;

            ContentValues sourceValues = new ContentValues();
            sourceValues.put("task_id", id);
            sourceValues.put("title", title);
            sourceValues.put("uri", uri);
            db.insert(SOURCES, null, sourceValues);
            count++;
        }
    }

    public synchronized Task getTask(String id) {
        if (id == null || id.trim().isEmpty()) return null;

        Cursor cursor = null;
        Task task = null;
        try {
            cursor = getReadableDatabase().query(
                    TASKS,
                    new String[]{"task_id", "topic", "status", "progress",
                            "report", "search_entry_html", "error",
                            "created_at", "updated_at"},
                    "task_id = ?",
                    new String[]{id},
                    null, null, null, "1");
            if (cursor.moveToFirst()) {
                task = new Task(
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        cursor.getString(6),
                        cursor.getLong(7),
                        cursor.getLong(8),
                        loadSources(id));
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return task;
    }

    public synchronized List<Task> listTasks(int limit) {
        List<Task> tasks = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(
                    TASKS,
                    new String[]{"task_id", "topic", "status", "progress",
                            "report", "search_entry_html", "error",
                            "created_at", "updated_at"},
                    null, null, null, null,
                    "updated_at DESC",
                    Integer.toString(Math.max(1, Math.min(limit, 30))));

            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                tasks.add(new Task(
                        id,
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getInt(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        cursor.getString(6),
                        cursor.getLong(7),
                        cursor.getLong(8),
                        Collections.<Source>emptyList()));
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return tasks;
    }

    private List<Source> loadSources(String taskId) {
        List<Source> sources = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().query(
                    SOURCES,
                    new String[]{"title", "uri"},
                    "task_id = ?",
                    new String[]{taskId},
                    null, null, "_id ASC", "30");
            while (cursor.moveToNext()) {
                sources.add(new Source(cursor.getString(0), cursor.getString(1)));
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return sources;
    }

    private static String safeText(String value, int maxLength) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.length() > maxLength) clean = clean.substring(0, maxLength);
        return clean;
    }

    private static String safeUri(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String uri = value.trim();
        if (uri.length() > 1500) return null;

        android.net.Uri parsed = android.net.Uri.parse(uri);
        String scheme = parsed.getScheme();
        if (parsed.getHost() == null
                || !("https".equalsIgnoreCase(scheme)
                || "http".equalsIgnoreCase(scheme))) {
            return null;
        }
        return uri;
    }
}
