package com.ultron.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WakeWordService extends Service {

    public static final String ACTION_START = "com.ultron.assistant.action.START_WAKE";
    public static final String ACTION_STOP = "com.ultron.assistant.action.STOP_WAKE";

    private static final String CHANNEL_ID = "ultron_wake_engine";
    private static final int NOTIFICATION_ID = 4102;

    private static final int SAMPLE_RATE = 16000;
    private static final String MODEL_FOLDER = "vosk-model-small-en-us-0.15";
    private static final String MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final String WAKE_GRAMMAR =
            "[\"wake up ultron\", \"hey ultron\", \"[unk]\"]";

    private static final int BG = Color.rgb(5, 12, 23);
    private static final int CYAN = Color.rgb(69, 224, 239);
    private static final int GOLD = Color.rgb(242, 184, 82);
    private static final int WHITE = Color.rgb(229, 241, 249);
    private static final int MUTED = Color.rgb(147, 174, 190);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean running;
    private volatile boolean overlayVisible;
    private volatile AudioRecord audioRecord;

    private Thread worker;
    private Model model;
    private Recognizer recognizer;

    private WindowManager windowManager;
    private android.view.View overlayView;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            running = false;
            stopRecorder();
            removeOverlay();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (worker != null && worker.isAlive()) {
            return START_NOT_STICKY;
        }

        try {
            startForegroundCompat(buildNotification(
                    "Preparing local wake-word engine…", true));
        } catch (SecurityException | IllegalStateException e) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        overlayVisible = false;
        worker = new Thread(this::prepareAndListen, "UltronWakeEngine");
        worker.start();
        return START_NOT_STICKY;
    }

    private void prepareAndListen() {
        try {
            File readyModel = prepareModel();
            if (!running) return;

            model = new Model(readyModel.getAbsolutePath());
            recognizer = new Recognizer(model, SAMPLE_RATE, WAKE_GRAMMAR);

            if (!running) return;

            setWakeEnabled(true);
            updateNotification("Listening on this device for “Wake up, ULTRON”", true);
            listenLoop();
        } catch (Exception e) {
            updateNotification("Wake engine stopped: " + safeMessage(e), false);
        } finally {
            running = false;
            setWakeEnabled(false);
            releaseAudioRecord();

            if (recognizer != null) {
                try { recognizer.close(); } catch (Exception ignored) { }
                recognizer = null;
            }
            if (model != null) {
                try { model.close(); } catch (Exception ignored) { }
                model = null;
            }

            mainHandler.post(() -> {
                removeOverlay();
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }
    }

    private File prepareModel() throws IOException {
        File modelDirectory = new File(getFilesDir(), "ultron-wake-model");
        File modelFile = new File(modelDirectory, "am/final.mdl");

        if (modelFile.isFile() && modelFile.length() > 0) {
            return modelDirectory;
        }

        File archive = new File(getCacheDir(), "ultron-wake-model.zip");
        downloadModel(archive);

        File extracted = new File(getFilesDir(), "ultron-wake-model-extract");
        deleteRecursively(extracted);
        if (!extracted.mkdirs() && !extracted.isDirectory()) {
            throw new IOException("Could not create model folder.");
        }

        unzipModel(archive, extracted);
        File extractedModel = new File(extracted, MODEL_FOLDER);
        File extractedModelFile = new File(extractedModel, "am/final.mdl");

        if (!extractedModelFile.isFile()) {
            deleteRecursively(extracted);
            throw new IOException("The downloaded speech model is incomplete.");
        }

        deleteRecursively(modelDirectory);
        if (!extractedModel.renameTo(modelDirectory)) {
            throw new IOException("Could not install the speech model.");
        }

        // Keep the app cache clean after a successful model installation.
        // The extracted model remains in the app's private internal storage.
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        return modelDirectory;
    }

    private void downloadModel(File archive) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(90000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "ULTRON-Android/0.2");
            connection.connect();

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Speech model download returned HTTP " + status);
            }

            long length = connection.getContentLengthLong();
            if (length > 100L * 1024L * 1024L) {
                throw new IOException("The speech model archive is larger than expected.");
            }

            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(
                         new FileOutputStream(archive))) {
                byte[] buffer = new byte[16 * 1024];
                long total = 0;
                int read;
                int lastProgress = -1;

                while (running && (read = in.read(buffer)) != -1) {
                    total += read;
                    if (total > 100L * 1024L * 1024L) {
                        throw new IOException("Speech model download exceeded the size limit.");
                    }
                    out.write(buffer, 0, read);

                    if (length > 0) {
                        int progress = (int) Math.min(100, total * 100 / length);
                        if (progress != lastProgress && progress % 10 == 0) {
                            lastProgress = progress;
                            updateNotification("Downloading offline voice model: "
                                    + progress + "%", true);
                        }
                    }
                }

                out.flush();
            }

            if (!running) {
                throw new IOException("Model download cancelled.");
            }
            if (!archive.isFile() || archive.length() < 1024 * 1024) {
                throw new IOException("The speech model download was incomplete.");
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void unzipModel(File archive, File destination) throws IOException {
        String prefix = MODEL_FOLDER + "/";
        String destinationPath = destination.getCanonicalPath() + File.separator;
        long expandedBytes = 0;
        int entryCount = 0;

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new java.io.FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];

            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > 3000) {
                    throw new IOException("The speech model archive has too many files.");
                }

                String name = entry.getName();
                if (!name.startsWith(prefix)) {
                    zip.closeEntry();
                    continue;
                }

                String relative = name.substring(prefix.length());
                if (relative.isEmpty()) {
                    zip.closeEntry();
                    continue;
                }

                File output = new File(destination, relative);
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(destinationPath)) {
                    throw new IOException("Unsafe path in speech model archive.");
                }

                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IOException("Could not create model folder.");
                    }
                } else {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Could not create model folder.");
                    }

                    try (BufferedOutputStream out = new BufferedOutputStream(
                            new FileOutputStream(output))) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            expandedBytes += read;
                            if (expandedBytes > 250L * 1024L * 1024L) {
                                throw new IOException("Speech model expanded beyond the size limit.");
                            }
                            out.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private void listenLoop() throws IOException {
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (minBuffer <= 0) {
            throw new IOException("This device could not initialize microphone audio.");
        }

        int bufferSize = Math.max(minBuffer * 2, 8192);
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IOException("Microphone initialization failed.");
        }

        audioRecord = recorder;

        try {
            recorder.startRecording();
            byte[] buffer = new byte[bufferSize];

            while (running) {
                int count = recorder.read(buffer, 0, buffer.length);
                if (count <= 0) {
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new IOException("The microphone connection was interrupted.");
                    }
                    continue;
                }

                if (recognizer.acceptWaveForm(buffer, count)) {
                    String result = extractText(recognizer.getResult(), "text");
                    checkWakePhrase(result);
                } else {
                    String partial = extractText(recognizer.getPartialResult(), "partial");
                    checkWakePhrase(partial);
                }
            }
        } finally {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
            }
            recorder.release();
            if (audioRecord == recorder) audioRecord = null;
        }
    }

    private String extractText(String json, String key) {
        try {
            return new JSONObject(json).optString(key, "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void checkWakePhrase(String text) {
        if (overlayVisible || text == null) return;

        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (normalized.contains("wake up ultron")
                || normalized.contains("hey ultron")) {
            overlayVisible = true;
            updateNotification("ULTRON heard the wake phrase", true);
            mainHandler.post(this::showWakeOverlay);
        }
    }

    private void showWakeOverlay() {
        if (!running || overlayView != null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            updateNotification("Overlay permission was removed. Wake engine stopped.", false);
            running = false;
            stopRecorder();
            stopSelf();
            return;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(15), dp(18), dp(15));
        card.setBackground(panelBackground(Color.rgb(8, 23, 38), CYAN, 20));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView emblem = overlayText("U", 24, GOLD, true);
        emblem.setGravity(Gravity.CENTER);
        top.addView(emblem, new LinearLayout.LayoutParams(dp(38), dp(38)));

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(11), 0, 0, 0);

        TextView title = overlayText("ULTRON IS AWAKE", 15, CYAN, true);
        title.setLetterSpacing(0.08f);
        titleBlock.addView(title);

        TextView subtitle = overlayText("I heard your wake phrase", 11, MUTED, false);
        titleBlock.addView(subtitle);

        top.addView(titleBlock, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = overlayText("×", 26, WHITE, false);
        close.setGravity(Gravity.CENTER);
        top.addView(close, new LinearLayout.LayoutParams(dp(34), dp(38)));
        close.setOnClickListener(v -> dismissWakeOverlay());

        card.addView(top);

        TextView open = overlayText("OPEN ASSISTANT  →", 12, BG, true);
        open.setGravity(Gravity.CENTER);
        open.setPadding(dp(12), dp(13), dp(12), dp(13));
        open.setBackground(panelBackground(CYAN, CYAN, 12));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        openParams.topMargin = dp(14);
        card.addView(open, openParams);
        open.setOnClickListener(v -> openAssistant());

        TextView privacy = overlayText("Wake detection is processed on this device.", 9, MUTED, false);
        privacy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        privacyParams.topMargin = dp(9);
        card.addView(privacy, privacyParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(320),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(54);

        try {
            windowManager.addView(card, params);
            overlayView = card;
        } catch (Exception e) {
            overlayVisible = false;
            updateNotification("Could not display ULTRON overlay", true);
        }
    }

    private void dismissWakeOverlay() {
        removeOverlay();
        overlayVisible = false;
        if (running) {
            updateNotification("Listening locally for “Wake up, ULTRON”", true);
        }
    }

    private void openAssistant() {
        removeOverlay();
        running = false;
        stopRecorder();

        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(MainActivity.ACTION_FROM_WAKE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        try {
            startActivity(intent);
        } catch (Exception e) {
            updateNotification("Open ULTRON from its notification", false);
        }

        stopSelf();
    }

    private void removeOverlay() {
        if (overlayView != null && windowManager != null) {
            try {
                windowManager.removeView(overlayView);
            } catch (Exception ignored) { }
            overlayView = null;
        }
    }

    private void stopRecorder() {
        AudioRecord current = audioRecord;
        if (current != null
                && current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            try { current.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    private void releaseAudioRecord() {
        AudioRecord current = audioRecord;
        audioRecord = null;
        if (current != null) {
            if (current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                try { current.stop(); } catch (IllegalStateException ignored) { }
            }
            try { current.release(); } catch (Exception ignored) { }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "ULTRON Wake Engine",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Visible status while local wake phrase detection is enabled.");
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String message, boolean ongoing) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 4103, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, WakeWordService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 4104, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("ULTRON wake engine")
                .setContentText(message)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop listening",
                        stopPendingIntent);

        return builder.build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String message, boolean ongoing) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(message, ongoing));
        }
    }

    private GradientDrawable panelBackground(int color, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private TextView overlayText(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "check microphone and internet connection";
        }
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private void setWakeEnabled(boolean enabled) {
        getSharedPreferences("ultron_state", MODE_PRIVATE)
                .edit().putBoolean("wake_enabled", enabled).apply();
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @Override
    public void onDestroy() {
        running = false;
        setWakeEnabled(false);
        stopRecorder();
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
