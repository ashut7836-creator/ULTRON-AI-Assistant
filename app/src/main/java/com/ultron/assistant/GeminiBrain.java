package com.ultron.assistant;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.firebase.ai.FirebaseAI;
import com.google.firebase.ai.GenerativeModel;
import com.google.firebase.ai.java.GenerativeModelFutures;
import com.google.firebase.ai.type.Content;
import com.google.firebase.ai.type.GenerateContentResponse;
import com.google.firebase.ai.type.GenerativeBackend;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Gemini conversation layer for ULTRON.
 * Device actions are intentionally not executed from model text.
 */
public final class GeminiBrain {

    private static final String MODEL_NAME = "gemini-4-hydrogen";
    private static final int MAX_HISTORY_TURNS = 6;

    private static final String SYSTEM_INSTRUCTIONS =
            "You are ULTRON, a helpful Android personal assistant. " +
            "Understand natural language and answer clearly and naturally. " +
            "Reply in the language used by the user, including Hindi or English. " +
            "Be concise for spoken answers unless the user asks for detail. " +
            "You are currently in conversation-only mode: you cannot open apps, " +
            "send messages, make calls, change settings, or operate the device. " +
            "Never claim that you performed a device action. Explain what you can " +
            "help with, and do not invent device state, screen contents, or results. " +
            "When a request needs a device action, say that the relevant permissioned " +
            "action feature is not connected yet.";

    private final GenerativeModelFutures model;
    private final Deque<String> history = new ArrayDeque<>();

    public interface Callback {
        void onSuccess(String answer);
        void onError(String message, Throwable cause);
    }

    private GeminiBrain(GenerativeModelFutures model) {
        this.model = model;
    }

    public static GeminiBrain create() {
        GenerativeModel generativeModel =
                FirebaseAI.getInstance(GenerativeBackend.googleAI())
                        .generativeModel(MODEL_NAME);
        return new GeminiBrain(GenerativeModelFutures.from(generativeModel));
    }

    public void ask(String userMessage, Callback callback) {
        if (callback == null) return;

        if (userMessage == null || userMessage.trim().isEmpty()) {
            callback.onError("Please enter a command first.", null);
            return;
        }

        final String cleanMessage = userMessage.trim();
        final String prompt = buildPrompt(cleanMessage);

        try {
            Content content = new Content.Builder()
                    .addText(prompt)
                    .build();

            ListenableFuture<GenerateContentResponse> request =
                    model.generateContent(content);

            Futures.addCallback(request, new FutureCallback<GenerateContentResponse>() {
                @Override
                public void onSuccess(GenerateContentResponse response) {
                    String answer = response == null ? null : response.getText();
                    if (answer == null || answer.trim().isEmpty()) {
                        callback.onError(
                                "Gemini returned an empty response. Please try again.", null);
                        return;
                    }

                    remember(cleanMessage, answer.trim());
                    callback.onSuccess(answer.trim());
                }

                @Override
                public void onFailure(Throwable error) {
                    callback.onError(
                            "I couldn't reach Gemini. Check the internet connection and " +
                            "Firebase AI Logic / App Check setup, then try again.",
                            error);
                }
            }, MoreExecutors.directExecutor());

        } catch (Exception error) {
            callback.onError(
                    "ULTRON couldn't start the Gemini request. Please try again.",
                    error);
        }
    }

    private synchronized String buildPrompt(String userMessage) {
        StringBuilder prompt = new StringBuilder(SYSTEM_INSTRUCTIONS);

        if (!history.isEmpty()) {
            prompt.append("\n\nRecent conversation:\n");
            for (String turn : history) {
                prompt.append(turn).append('\n');
            }
        }

        prompt.append("\nCurrent user message:\n")
                .append(userMessage)
                .append("\n\nRespond to the current user message.");
        return prompt.toString();
    }

    private synchronized void remember(String userMessage, String answer) {
        history.addLast("User: " + userMessage + "\nULTRON: " + answer);
        while (history.size() > MAX_HISTORY_TURNS) {
            history.removeFirst();
        }
    }

    public synchronized void clearConversation() {
        history.clear();
    }
}
