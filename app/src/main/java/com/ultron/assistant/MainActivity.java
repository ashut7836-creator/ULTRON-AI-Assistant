package com.ultron.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.FirebaseApp;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int SPEECH_REQUEST = 1001;
    private static final int REQUEST_WAKE_MIC = 2201;
    private static final int REQUEST_WAKE_NOTIFICATIONS = 2202;
    private static final int REQUEST_WAKE_OVERLAY = 2203;

    public static final String ACTION_FROM_WAKE =
            "com.ultron.assistant.action.FROM_WAKE";

    private static final int BG = Color.rgb(4, 9, 18);
    private static final int PANEL = Color.rgb(9, 21, 35);
    private static final int PANEL_LIGHT = Color.rgb(13, 31, 49);
    private static final int CYAN = Color.rgb(69, 224, 239);
    private static final int CYAN_DIM = Color.rgb(35, 104, 125);
    private static final int GOLD = Color.rgb(242, 184, 82);
    private static final int WHITE = Color.rgb(229, 241, 249);
    private static final int MUTED = Color.rgb(119, 153, 173);

    private EditText commandInput;
    private TextView resultText;
    private TextView connectionText;
    private CoreView coreView;
    private TextView wakeEngineButton;
    private boolean waitingForOverlaySettings;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private SpeechOutput speechOutput;
    private GeminiBrain geminiBrain;
    private UltronMemory ultronMemory;
    private String pendingMemorySuggestion;
    private TextView aiLinkStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.getDecorView().setSystemUiVisibility(0);

        buildInterface();
        speechOutput = new SpeechOutput(this);
        ultronMemory = new UltronMemory(this);
        initializeGeminiBrain();

        if (ACTION_FROM_WAKE.equals(getIntent().getAction())) {
            uiHandler.postDelayed(this::startVoiceInput, 700);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshWakeButton();

        if (waitingForOverlaySettings) {
            waitingForOverlaySettings = false;
            if (Settings.canDrawOverlays(this)) {
                requestWakePrerequisites();
            } else if (connectionText != null) {
                connectionText.setText("OVERLAY PERMISSION NOT GRANTED");
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_FROM_WAKE.equals(intent.getAction())) {
            if (connectionText != null) {
                connectionText.setText("WAKE PHRASE DETECTED");
            }
            if (resultText != null) {
                resultText.setText("ULTRON heard you. Speak your command.");
            }
            uiHandler.postDelayed(this::startVoiceInput, 700);
        }
    }

    private void buildInterface() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));
        root.setBackgroundColor(BG);
        scrollView.addView(root);

        root.addView(buildHeader(), matchWrap());

        addSpace(root, 26);

        TextView section = text("PERSONAL AI SYSTEM", 11, MUTED, true);
        section.setLetterSpacing(0.20f);
        section.setGravity(Gravity.CENTER);
        root.addView(section, matchWrap());

        addSpace(root, 8);

        TextView greeting = text("How can I assist you?", 23, WHITE, true);
        greeting.setGravity(Gravity.CENTER);
        root.addView(greeting, matchWrap());

        addSpace(root, 10);

        coreView = new CoreView();
        LinearLayout.LayoutParams coreParams =
                new LinearLayout.LayoutParams(dp(258), dp(258));
        coreParams.gravity = Gravity.CENTER;
        root.addView(coreView, coreParams);
        coreView.setOnClickListener(v -> startVoiceInput());

        connectionText = text("SYSTEM STANDBY  /  AWAITING INPUT", 11, CYAN, true);
        connectionText.setLetterSpacing(0.12f);
        connectionText.setGravity(Gravity.CENTER);
        root.addView(connectionText, matchWrap());

        addSpace(root, 20);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.addView(metricTile("VOICE", "READY"), weightParams(1, 0, dp(6)));
        metrics.addView(metricTile("DEVICE", "CONNECTED"), weightParams(1, dp(3), dp(3)));
        metrics.addView(metricTile("AI LINK", "PENDING"), weightParams(1, dp(6), 0));
        root.addView(metrics, matchWrap());

        addSpace(root, 19);

        wakeEngineButton = hudButton(
                "ENABLE WAKE PHRASE", PANEL_LIGHT, CYAN, CYAN_DIM, true);
        root.addView(wakeEngineButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        wakeEngineButton.setOnClickListener(v -> toggleWakeEngine());

        addSpace(root, 8);

        TextView wakeHint = text(
                "Optional local listening. First use downloads an offline English model (~40 MB).",
                10, MUTED, false);
        wakeHint.setGravity(Gravity.CENTER);
        root.addView(wakeHint, matchWrap());

        addSpace(root, 22);

        TextView commandLabel = text("COMMAND CONSOLE", 11, CYAN, true);
        commandLabel.setLetterSpacing(0.18f);
        root.addView(commandLabel, matchWrap());

        addSpace(root, 10);

        commandInput = new EditText(this);
        commandInput.setTextColor(WHITE);
        commandInput.setHintTextColor(Color.rgb(91, 119, 139));
        commandInput.setHint("Type a command for ULTRON...");
        commandInput.setTextSize(15);
        commandInput.setSingleLine(false);
        commandInput.setMinLines(1);
        commandInput.setMaxLines(3);
        commandInput.setPadding(dp(16), dp(13), dp(16), dp(13));
        commandInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        commandInput.setBackground(panelBackground(PANEL_LIGHT, CYAN_DIM, 15));
        root.addView(commandInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        addSpace(root, 10);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        TextView voiceButton = hudButton("MIC  /  SPEAK", CYAN, BG, CYAN, true);
        controls.addView(voiceButton, weightParams(1, 0, dp(5)));
        voiceButton.setOnClickListener(v -> startVoiceInput());

        TextView runButton = hudButton("RUN COMMAND", PANEL_LIGHT, WHITE, CYAN_DIM, true);
        controls.addView(runButton, weightParams(1, dp(5), 0));
        runButton.setOnClickListener(v ->
                handleCommand(commandInput.getText().toString()));

        root.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        addSpace(root, 25);

        LinearLayout quickHeader = new LinearLayout(this);
        quickHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView quickTitle = text("QUICK ACCESS", 11, CYAN, true);
        quickTitle.setLetterSpacing(0.18f);
        quickHeader.addView(quickTitle, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView quickHint = text("DEVICE SHORTCUTS", 9, MUTED, false);
        quickHint.setLetterSpacing(0.10f);
        quickHeader.addView(quickHint);
        root.addView(quickHeader, matchWrap());

        addSpace(root, 10);

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);

        View youtubeButton = appButton("▶", "YOUTUBE");
        quickRow.addView(youtubeButton, weightParams(1, 0, dp(5)));
        youtubeButton.setOnClickListener(v ->
                openApp("com.google.android.youtube", "YouTube"));

        View whatsappButton = appButton("◉", "WHATSAPP");
        quickRow.addView(whatsappButton, weightParams(1, dp(5), 0));
        whatsappButton.setOnClickListener(v ->
                openApp("com.whatsapp", "WhatsApp"));

        root.addView(quickRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        addSpace(root, 22);

        LinearLayout responseCard = new LinearLayout(this);
        responseCard.setOrientation(LinearLayout.VERTICAL);
        responseCard.setPadding(dp(15), dp(13), dp(15), dp(15));
        responseCard.setBackground(panelBackground(PANEL, Color.rgb(25, 62, 81), 14));

        TextView responseTitle = text("SYSTEM MESSAGE", 10, GOLD, true);
        responseTitle.setLetterSpacing(0.18f);
        responseCard.addView(responseTitle, matchWrap());

        addSpace(responseCard, 9);

        resultText = text(
                "ULTRON core initialized. AI connection will be enabled in the next stage.",
                13, WHITE, false);
        resultText.setGravity(Gravity.START);
        if ((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            resultText.setTextIsSelectable(true);
        }
        responseCard.addView(resultText, matchWrap());

        root.addView(responseCard, matchWrap());

        addSpace(root, 18);

        TextView footer = text("ULTRON  //  PERSONAL ASSISTANT", 9, MUTED, false);
        footer.setLetterSpacing(0.17f);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, matchWrap());

        setContentView(scrollView);
        refreshWakeButton();
    }

    private void toggleWakeEngine() {
        boolean enabled = getSharedPreferences("ultron_state", MODE_PRIVATE)
                .getBoolean("wake_enabled", false);

        if (enabled) {
            Intent stop = new Intent(this, WakeWordService.class);
            stop.setAction(WakeWordService.ACTION_STOP);
            try {
                startService(stop);
            } catch (Exception e) {
                stopService(new Intent(this, WakeWordService.class));
            }
            connectionText.setText("WAKE ENGINE STOPPING");
            resultText.setText("ULTRON wake phrase listening is being turned off.");
            refreshWakeButton();
            return;
        }

        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlaySettings = true;
            connectionText.setText("OVERLAY PERMISSION REQUIRED");
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(settingsIntent, REQUEST_WAKE_OVERLAY);
            } catch (Exception e) {
                waitingForOverlaySettings = false;
                resultText.setText("Open Android Settings and allow ULTRON to display over other apps.");
            }
            return;
        }

        requestWakePrerequisites();
    }

    private void requestWakePrerequisites() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_WAKE_MIC);
            return;
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_WAKE_NOTIFICATIONS);
            return;
        }

        startWakeEngine();
    }

    private void startWakeEngine() {
        Intent start = new Intent(this, WakeWordService.class);
        start.setAction(WakeWordService.ACTION_START);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(start);
            } else {
                startService(start);
            }

            getSharedPreferences("ultron_state", MODE_PRIVATE)
                    .edit().putBoolean("wake_enabled", true).apply();

            connectionText.setText("WAKE ENGINE STARTING");
            resultText.setText(
                    "ULTRON is preparing its offline wake-word model. " +
                    "A persistent notification will show while listening is active.");
            refreshWakeButton();
        } catch (Exception e) {
            connectionText.setText("WAKE ENGINE COULD NOT START");
            resultText.setText(
                    "Android could not start microphone listening. " +
                    "Open ULTRON and try again while it is on screen.");
        }
    }

    private void refreshWakeButton() {
        if (wakeEngineButton == null) return;

        boolean enabled = getSharedPreferences("ultron_state", MODE_PRIVATE)
                .getBoolean("wake_enabled", false);
        wakeEngineButton.setText(
                enabled ? "STOP WAKE PHRASE" : "ENABLE WAKE PHRASE");
        wakeEngineButton.setTextColor(enabled ? GOLD : CYAN);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_WAKE_MIC
                || requestCode == REQUEST_WAKE_NOTIFICATIONS) {
            boolean micGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
            boolean notificationsGranted = Build.VERSION.SDK_INT < 33
                    || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;

            if (!micGranted) {
                connectionText.setText("MICROPHONE PERMISSION NOT GRANTED");
                resultText.setText("ULTRON needs microphone permission to detect its wake phrase.");
            } else if (!notificationsGranted) {
                connectionText.setText("NOTIFICATION PERMISSION REQUIRED");
                resultText.setText("Allow notifications so Android can clearly show when wake listening is active.");
            } else {
                startWakeEngine();
            }
        }
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView emblem = text("U", 23, CYAN, true);
        emblem.setGravity(Gravity.CENTER);
        GradientDrawable emblemShape = new GradientDrawable();
        emblemShape.setColor(PANEL_LIGHT);
        emblemShape.setCornerRadius(dp(11));
        emblemShape.setStroke(dp(1), CYAN_DIM);
        emblem.setBackground(emblemShape);
        header.addView(emblem, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout brandBlock = new LinearLayout(this);
        brandBlock.setOrientation(LinearLayout.VERTICAL);
        brandBlock.setPadding(dp(11), 0, 0, 0);

        TextView brand = text("ULTRON", 21, WHITE, true);
        brand.setLetterSpacing(0.16f);
        brandBlock.addView(brand, matchWrap());

        TextView subtitle = text("INTELLIGENCE INTERFACE", 9, MUTED, false);
        subtitle.setLetterSpacing(0.10f);
        brandBlock.addView(subtitle, matchWrap());

        header.addView(brandBlock, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10), dp(7), dp(10), dp(7));
        status.setBackground(panelBackground(PANEL, Color.rgb(32, 83, 87), 30));

        View led = new View(this);
        GradientDrawable ledShape = new GradientDrawable();
        ledShape.setShape(GradientDrawable.OVAL);
        ledShape.setColor(CYAN);
        led.setBackground(ledShape);
        status.addView(led, new LinearLayout.LayoutParams(dp(7), dp(7)));

        TextView online = text("  ONLINE", 9, CYAN, true);
        online.setLetterSpacing(0.08f);
        status.addView(online, matchWrap());
        header.addView(status);

        return header;
    }

    private View metricTile(String label, String value) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(5), dp(12), dp(5), dp(12));
        tile.setBackground(panelBackground(PANEL, Color.rgb(25, 54, 72), 12));

        TextView valueView = text(value, 10, WHITE, true);
        valueView.setGravity(Gravity.CENTER);
        valueView.setLetterSpacing(0.04f);
        if ("AI LINK".equals(label)) {
            aiLinkStatus = valueView;
        }
        tile.addView(valueView, matchWrap());

        addSpace(tile, 5);

        TextView labelView = text(label, 9, MUTED, false);
        labelView.setGravity(Gravity.CENTER);
        labelView.setLetterSpacing(0.12f);
        tile.addView(labelView, matchWrap());

        return tile;
    }

    private View appButton(String symbol, String label) {
        LinearLayout button = new LinearLayout(this);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackground(panelBackground(
                PANEL_LIGHT, Color.rgb(34, 76, 99), 12));

        TextView icon = text(symbol, 20, GOLD, true);
        button.addView(icon, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView name = text("  " + label, 11, WHITE, true);
        name.setLetterSpacing(0.06f);
        button.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        return button;
    }

    private TextView hudButton(String label, int background, int foreground,
                               int stroke, boolean bold) {
        TextView button = text(label, 11, foreground, bold);
        button.setGravity(Gravity.CENTER);
        button.setLetterSpacing(0.09f);
        button.setBackground(panelBackground(background, stroke, 12));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                "Speak a command for ULTRON");

        try {
            connectionText.setText("VOICE INPUT  /  LISTENING");
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (Exception e) {
            connectionText.setText("VOICE INPUT UNAVAILABLE");
            Toast.makeText(this,
                    "No speech recognition service is available.",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_WAKE_OVERLAY) {
            waitingForOverlaySettings = false;
            if (Settings.canDrawOverlays(this)) {
                requestWakePrerequisites();
            } else {
                connectionText.setText("OVERLAY PERMISSION NOT GRANTED");
            }
            return;
        }

        if (requestCode == SPEECH_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> matches = data.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS);
                if (matches != null && !matches.isEmpty()) {
                    String spokenCommand = matches.get(0);
                    commandInput.setText(spokenCommand);
                    handleCommand(spokenCommand);
                    return;
                }
            }
            connectionText.setText("SYSTEM STANDBY  /  AWAITING INPUT");
        }
    }

    private void handleCommand(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);

        if (normalized.isEmpty()) {
            resultText.setText("Enter a command or tap the voice control.");
            speakResponse("Please enter a command or use the voice control.");
            return;
        }

        // User-controlled memory commands are handled locally and never sent to Gemini.
        if (ultronMemory != null) {
            try {
                String memoryResult = ultronMemory.handleCommand(command);
                if (memoryResult != null) {
                    pendingMemorySuggestion = null;
                    connectionText.setText("MEMORY  /  UPDATED");
                    resultText.setText(memoryResult);
                    speakResponse(memoryResult);
                    return;
                }
            } catch (RuntimeException memoryError) {
                connectionText.setText("MEMORY  /  UNAVAILABLE");
                resultText.setText("ULTRON couldn't access its saved memory on this device.");
                speakResponse("ULTRON memory is temporarily unavailable.");
                return;
            }
        }

        // A pending suggestion is only accepted or declined by a clear user reply.
        // Any unrelated next command dismisses it instead of leaving a stale prompt.
        if (pendingMemorySuggestion != null) {
            String choice = normalized.replaceAll("[,!?]+", " ")
                    .replaceAll("\\s+", " ").trim();
            if (isMemoryAcceptCommand(choice)) {
                String approvedFact = pendingMemorySuggestion;
                pendingMemorySuggestion = null;
                UltronMemory.SaveResult saveResult = ultronMemory.remember(approvedFact);
                String saveMessage;
                if (saveResult == UltronMemory.SaveResult.SAVED) {
                    saveMessage = "Saved with your approval in ULTRON's private memory on this device.";
                } else if (saveResult == UltronMemory.SaveResult.DUPLICATE) {
                    saveMessage = "That note is already saved in ULTRON's memory.";
                } else {
                    saveMessage = "ULTRON could not save that suggestion.";
                }
                connectionText.setText("MEMORY  /  UPDATED");
                resultText.setText(saveMessage);
                speakResponse(saveMessage);
                return;
            } else if (isMemoryDeclineCommand(choice)) {
                pendingMemorySuggestion = null;
                String declineMessage = "Okay. I won't save that suggestion.";
                connectionText.setText("MEMORY  /  NOT SAVED");
                resultText.setText(declineMessage);
                speakResponse(declineMessage);
                return;
            } else {
                pendingMemorySuggestion = null;
            }
        }

        // Run only explicitly supported, permission-conscious actions locally.
        // Unknown requests continue to Gemini as ordinary conversation.
        String actionResult = DeviceCommandRouter.handle(this, command);
        if (actionResult != null) {
            connectionText.setText("DEVICE ACTION  /  COMPLETE");
            resultText.setText(actionResult);
            speakResponse(actionResult);
            return;
        }

        if (normalized.equals("youtube")) {
            openApp("com.google.android.youtube", "YouTube");
            return;
        }

        if (normalized.equals("whatsapp")) {
            openApp("com.whatsapp", "WhatsApp");
            return;
        }

        askGemini(command);
    }

    private void initializeGeminiBrain() {
        if (FirebaseApp.getApps(this).isEmpty()) {
            setAiLinkStatus("SETUP");
            return;
        }

        try {
            geminiBrain = GeminiBrain.create(ultronMemory, this::handleAgentToolCall);
            setAiLinkStatus("READY");
        } catch (Exception e) {
            geminiBrain = null;
            setAiLinkStatus("OFFLINE");
        }
    }

    /**
     * Receives Gemini's structured tool request. DeviceCommandRouter revalidates
     * every argument against its own fixed allowlist before opening anything.
     */
    private void handleAgentToolCall(
            String toolName,
            Map<String, String> arguments,
            GeminiBrain.ToolCallback callback) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                callback.onResult(false, "ULTRON is closing. The action was cancelled.");
                return;
            }

            if ("draft_whatsapp_message".equals(toolName)) {
                String draftText = arguments == null ? null : arguments.get("message");
                if (draftText == null || draftText.trim().isEmpty()
                        || draftText.length() > 1000) {
                    callback.onResult(false, "The WhatsApp draft was invalid. Nothing was opened or sent.");
                    return;
                }

                new AlertDialog.Builder(this)
                        .setTitle("Review WhatsApp draft")
                        .setMessage(
                                "ULTRON wants to open this draft:\n\n"
                                + draftText.trim()
                                + "\n\nNothing will be sent automatically. "
                                + "After WhatsApp opens, choose the recipient, review the text, "
                                + "and tap Send yourself.")
                        .setNegativeButton("Cancel", (dialog, which) ->
                                callback.onResult(false, "Cancelled. No message was sent."))
                        .setPositiveButton("Open draft", (dialog, which) ->
                                executeAgentTool(toolName, arguments, callback))
                        .setCancelable(false)
                        .show();
                return;
            }

            executeAgentTool(toolName, arguments, callback);
        });
    }

    private void executeAgentTool(
            String toolName,
            Map<String, String> arguments,
            GeminiBrain.ToolCallback callback) {
        DeviceCommandRouter.ToolActionResult result =
                DeviceCommandRouter.executeTool(this, toolName, arguments);
        callback.onResult(result.success, result.message);
    }

    private void setAiLinkStatus(String status) {
        if (aiLinkStatus != null) {
            aiLinkStatus.setText(status);
        }
    }

    private void askGemini(String command) {
        pendingMemorySuggestion = ultronMemory == null
                ? null : ultronMemory.suggestFromMessage(command);

        if (geminiBrain == null) {
            connectionText.setText("GEMINI SETUP REQUIRED");
            resultText.setText(appendMemorySuggestion(
                    "The Gemini connection is prepared in ULTRON, but its Firebase project " +
                    "is not connected yet. Complete the Firebase AI Logic setup and add " +
                    "app/google-services.json to enable AI replies."));
            speakResponse("Gemini setup is still required.");
            return;
        }

        connectionText.setText("GEMINI  /  THINKING");
        resultText.setText("ULTRON is thinking...");
        geminiBrain.ask(command, new GeminiBrain.Callback() {
            @Override
            public void onSuccess(String answer) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    connectionText.setText("GEMINI  /  RESPONSE READY");
                    resultText.setText(appendMemorySuggestion(answer));
                    setAiLinkStatus("ONLINE");
                    speakResponse(answer);
                });
            }

            @Override
            public void onError(String message, Throwable cause) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    connectionText.setText("GEMINI  /  CONNECTION ISSUE");
                    String displayMessage = message;
                    if ((getApplicationInfo().flags
                            & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                            && cause != null) {
                        displayMessage += "\n\nDEBUG DIAGNOSTICS\n"
                                + summarizeGeminiError(cause)
                                + "\n\nAPP CHECK DEBUG TOKEN (KEEP PRIVATE)\n"
                                + readDebugAppCheckSecret();
                    }
                    resultText.setText(appendMemorySuggestion(displayMessage));
                    setAiLinkStatus("CHECK LINK");
                    speakResponse(message);
                });
            }
        });
    }

    private String appendMemorySuggestion(String message) {
        if (pendingMemorySuggestion == null) return message;
        return message + "\n\nMEMORY SUGGESTION\nShould I save \"" 
                + pendingMemorySuggestion
                + "\" to ULTRON's private memory on this device? Reply YES or NO. "
                + "Nothing is saved unless you approve.";
    }

    private boolean isMemoryAcceptCommand(String command) {
        return "yes".equals(command) || "yes please".equals(command)
                || "yes save it".equals(command) || "save it".equals(command)
                || "save suggestion".equals(command) || "remember this".equals(command);
    }

    private boolean isMemoryDeclineCommand(String command) {
        return "no".equals(command) || "no thanks".equals(command)
                || "dont save".equals(command) || "do not save".equals(command)
                || "discard".equals(command) || "dismiss".equals(command);
    }

    private void openApp(String packageName, String appName) {
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);

        if (launchIntent == null) {
            resultText.setText(appName + " is not installed or cannot be opened.");
            speakResponse(appName + " is not installed on this device.");
            return;
        }

        try {
            startActivity(launchIntent);
            connectionText.setText("COMMAND EXECUTED  /  " + appName.toUpperCase(Locale.ROOT));
            resultText.setText("Opening " + appName + " on this device.");
            speakResponse("Opening " + appName + ".");
        } catch (Exception e) {
            resultText.setText("Could not open " + appName + ".");
            speakResponse("I could not open " + appName + ".");
        }
    }

    /**
     * Reads the Android Debug App Check provider's locally persisted secret.
     * This uses an internal Firebase preference name for temporary debugging only.
     * The token is shown only in debuggable builds and is never logged or spoken.
     */
    private String readDebugAppCheckSecret() {
        try {
            com.google.firebase.FirebaseApp app =
                    com.google.firebase.FirebaseApp.getInstance();
            int base64Flags = android.util.Base64.URL_SAFE
                    | android.util.Base64.NO_PADDING
                    | android.util.Base64.NO_WRAP;
            String persistenceKey =
                    android.util.Base64.encodeToString(
                            app.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            base64Flags)
                    + "+"
                    + android.util.Base64.encodeToString(
                            app.getOptions().getApplicationId().getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8),
                            base64Flags);
            String preferencesName =
                    "com.google.firebase.appcheck.debug.store." + persistenceKey;
            String secretKey =
                    "com.google.firebase.appcheck.debug.DEBUG_SECRET";

            android.content.SharedPreferences preferences =
                    getSharedPreferences(preferencesName, MODE_PRIVATE);
            String secret = preferences.getString(secretKey, null);

            if (secret == null || secret.trim().isEmpty()) {
                return "Not found in this app installation. Trigger one Gemini request and check again.";
            }
            return secret;
        } catch (Exception error) {
            return "Could not read the local debug token: "
                    + error.getClass().getSimpleName();
        }
    }

    /**
     * Shows a short exception chain only in debuggable builds.
     * Never displays stack traces or credential-like values.
     */
    private String summarizeGeminiError(Throwable error) {
        StringBuilder details = new StringBuilder();
        Throwable current = error;
        int depth = 0;

        while (current != null && depth < 5) {
            if (depth > 0) {
                details.append("\nCaused by: ");
            }

            details.append(current.getClass().getSimpleName());

            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                String safeMessage = message.trim()
                        .replaceAll("(?i)(key|token|authorization)=([^&\\s]+)",
                                "$1=[REDACTED]")
                        .replaceAll("AIza[0-9A-Za-z_-]{20,}", "[REDACTED_API_KEY]");
                details.append(": ").append(safeMessage);
            }

            Throwable next = current.getCause();
            if (next == current) {
                break;
            }
            current = next;
            depth++;
        }

        return details.toString();
    }

    private void speakResponse(String message) {
        if (speechOutput != null) {
            speechOutput.speak(message);
        }
    }

    @Override
    protected void onDestroy() {
        if (speechOutput != null) {
            speechOutput.shutdown();
        }
        super.onDestroy();
    }

    private GradientDrawable panelBackground(int color, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightParams(float weight, int left, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        params.setMargins(left, 0, right, 0);
        return params;
    }

    private void addSpace(LinearLayout layout, int height) {
        View spacer = new View(this);
        layout.addView(spacer, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class CoreView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF ring = new RectF();

        CoreView() {
            super(MainActivity.this);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float radius = size * 0.43f;

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, radius,
                    new int[] { 0x5537D9EA, 0x2222A5C4, 0x00102030 },
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.4f));
            paint.setColor(Color.rgb(34, 111, 137));
            canvas.drawCircle(cx, cy, radius, paint);

            paint.setStrokeWidth(dp(2));
            paint.setColor(CYAN);
            ring.set(cx - radius * 0.92f, cy - radius * 0.92f,
                    cx + radius * 0.92f, cy + radius * 0.92f);
            canvas.drawArc(ring, -82, 104, false, paint);
            canvas.drawArc(ring, 102, 78, false, paint);
            canvas.drawArc(ring, 218, 96, false, paint);

            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.rgb(35, 87, 111));
            canvas.drawCircle(cx, cy, radius * 0.78f, paint);

            ring.set(cx - radius * 0.68f, cy - radius * 0.68f,
                    cx + radius * 0.68f, cy + radius * 0.68f);
            paint.setColor(Color.rgb(45, 135, 156));
            canvas.drawArc(ring, -35, 125, false, paint);
            canvas.drawArc(ring, 155, 105, false, paint);

            for (int i = 0; i < 72; i++) {
                canvas.save();
                canvas.rotate(i * 5f, cx, cy);
                paint.setStrokeWidth(i % 6 == 0 ? dp(1.6f) : dp(0.8f));
                paint.setColor(i % 6 == 0 ? CYAN : Color.rgb(48, 91, 112));
                float start = radius * 0.82f;
                float end = radius * (i % 6 == 0 ? 0.91f : 0.87f);
                canvas.drawLine(cx, cy - start, cx, cy - end, paint);
                canvas.restore();
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(cx, cy, radius * 0.48f,
                    new int[] { 0x663DE4F1, 0x2225AFCB, 0x00102030 },
                    null, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, radius * 0.48f, paint);
            paint.setShader(null);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.3f));
            paint.setColor(Color.rgb(70, 172, 190));
            canvas.drawCircle(cx, cy, radius * 0.45f, paint);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(GOLD);
            Path bolt = new Path();
            bolt.moveTo(cx + radius * 0.08f, cy - radius * 0.29f);
            bolt.lineTo(cx - radius * 0.20f, cy + radius * 0.02f);
            bolt.lineTo(cx - radius * 0.02f, cy + radius * 0.02f);
            bolt.lineTo(cx - radius * 0.10f, cy + radius * 0.29f);
            bolt.lineTo(cx + radius * 0.22f, cy - radius * 0.06f);
            bolt.lineTo(cx + radius * 0.04f, cy - radius * 0.06f);
            bolt.close();
            canvas.drawPath(bolt, paint);

            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(8));
            paint.setColor(CYAN);
            canvas.drawText("ULTRON CORE", cx, cy + radius * 0.59f, paint);

            paint.setTextSize(dp(7));
            paint.setColor(MUTED);
            canvas.drawText("VOICE INTERFACE", cx, cy + radius * 0.69f, paint);
        }
    }
}
