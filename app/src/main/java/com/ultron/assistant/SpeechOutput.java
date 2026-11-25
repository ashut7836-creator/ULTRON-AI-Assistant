package com.ultron.assistant;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;

import java.util.Locale;

/**
 * Small lifecycle-aware wrapper around Android's built-in text-to-speech engine.
 * No API key or network connection is required for the device's installed TTS voice.
 */
public final class SpeechOutput {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech textToSpeech;
    private boolean initialized;
    private boolean closed;
    private String pendingText;

    public SpeechOutput(Context context) {
        Context appContext = context.getApplicationContext();
        textToSpeech = new TextToSpeech(appContext, status ->
                mainHandler.post(() -> finishInitialization(status)));
    }

    private void finishInitialization(int status) {
        if (closed || textToSpeech == null || status != TextToSpeech.SUCCESS) {
            return;
        }

        int languageStatus = textToSpeech.setLanguage(Locale.getDefault());
        if (languageStatus == TextToSpeech.LANG_MISSING_DATA
                || languageStatus == TextToSpeech.LANG_NOT_SUPPORTED) {
            textToSpeech.setLanguage(Locale.US);
        }

        textToSpeech.setSpeechRate(1.0f);
        initialized = true;

        if (pendingText != null) {
            String queued = pendingText;
            pendingText = null;
            speakNow(queued);
        }
    }

    /** Speaks the latest assistant response, replacing any response still queued. */
    public void speak(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        String safeMessage = message.trim();
        mainHandler.post(() -> {
            if (closed) {
                return;
            }
            if (!initialized || textToSpeech == null) {
                pendingText = safeMessage;
                return;
            }
            speakNow(safeMessage);
        });
    }

    private void speakNow(String message) {
        if (closed || textToSpeech == null || !initialized) {
            return;
        }

        Bundle parameters = new Bundle();
        textToSpeech.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                parameters,
                "ultron-response-" + System.nanoTime());
    }

    public void stop() {
        mainHandler.post(() -> {
            if (textToSpeech != null) {
                textToSpeech.stop();
            }
            pendingText = null;
        });
    }

    /** Must be called by the owning Activity when it is destroyed. */
    public void shutdown() {
        mainHandler.post(() -> {
            closed = true;
            initialized = false;
            pendingText = null;
            if (textToSpeech != null) {
                textToSpeech.stop();
                textToSpeech.shutdown();
                textToSpeech = null;
            }
        });
    }
}
