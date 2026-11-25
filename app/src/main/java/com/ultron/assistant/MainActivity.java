package com.ultron.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int SPEECH_REQUEST = 1001;

    private EditText commandInput;
    private TextView resultText;
    private TextView connectionText;

    private final int backgroundColor = Color.rgb(7, 12, 25);
    private final int cyanColor = Color.rgb(47, 220, 235);
    private final int whiteColor = Color.rgb(238, 245, 255);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(backgroundColor);
        getWindow().setNavigationBarColor(backgroundColor);

        buildInterface();
    }

    private void buildInterface() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(28), dp(24), dp(28));
        root.setBackgroundColor(backgroundColor);

        scrollView.addView(root);

        TextView brand = text("ULTRON ⚡", 32, cyanColor, true);
        brand.setGravity(Gravity.CENTER);
        root.addView(brand, matchWrap());

        TextView subtitle = text(
                "YOUR PERSONAL AI ASSISTANT", 12,
                Color.LTGRAY, false
        );
        subtitle.setLetterSpacing(0.15f);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle, matchWrap());

        addSpace(root, 34);

        TextView orb = text("⚡", 52, cyanColor, true);
        orb.setGravity(Gravity.CENTER);

        GradientDrawable orbBackground = new GradientDrawable();
        orbBackground.setShape(GradientDrawable.OVAL);
        orbBackground.setColor(Color.rgb(15, 35, 55));
        orbBackground.setStroke(dp(2), cyanColor);
        orb.setBackground(orbBackground);

        LinearLayout.LayoutParams orbParams =
                new LinearLayout.LayoutParams(dp(118), dp(118));
        orbParams.gravity = Gravity.CENTER;
        root.addView(orb, orbParams);

        addSpace(root, 18);

        connectionText = text("READY FOR YOUR COMMAND", 13,
                cyanColor, true);
        connectionText.setGravity(Gravity.CENTER);
        root.addView(connectionText, matchWrap());

        addSpace(root, 28);

        Button voiceButton = makeButton(
                "🎙  TAP TO SPEAK", cyanColor, backgroundColor
        );
        root.addView(voiceButton, matchButton());
        voiceButton.setOnClickListener(v -> startVoiceInput());

        addSpace(root, 22);

        TextView prompt = text(
                "What can I help you with?", 18, whiteColor, true
        );
        root.addView(prompt, matchWrap());

        addSpace(root, 12);

        commandInput = new EditText(this);
        commandInput.setTextColor(whiteColor);
        commandInput.setHintTextColor(Color.GRAY);
        commandInput.setHint("Type a command...");
        commandInput.setTextSize(16);
        commandInput.setSingleLine(false);
        commandInput.setMinLines(1);
        commandInput.setMaxLines(3);
        commandInput.setPadding(dp(16), dp(12), dp(16), dp(12));
        commandInput.setImeOptions(EditorInfo.IME_ACTION_DONE);

        GradientDrawable inputBackground = new GradientDrawable();
        inputBackground.setColor(Color.rgb(17, 27, 43));
        inputBackground.setCornerRadius(dp(14));
        inputBackground.setStroke(dp(1), Color.rgb(49, 70, 93));
        commandInput.setBackground(inputBackground);

        root.addView(commandInput, matchButton());

        addSpace(root, 12);

        Button runButton = makeButton(
                "RUN COMMAND", Color.rgb(30, 49, 72), whiteColor
        );
        root.addView(runButton, matchButton());
        runButton.setOnClickListener(v ->
                handleCommand(commandInput.getText().toString())
        );

        addSpace(root, 24);

        TextView quickTitle = text(
                "QUICK ACTIONS", 12, Color.LTGRAY, true
        );
        quickTitle.setGravity(Gravity.CENTER);
        root.addView(quickTitle, matchWrap());

        addSpace(root, 12);

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);

        Button youtubeButton = makeButton(
                "YouTube", Color.rgb(35, 48, 67), whiteColor
        );
        Button whatsappButton = makeButton(
                "WhatsApp", Color.rgb(35, 48, 67), whiteColor
        );

        LinearLayout.LayoutParams halfButton =
                new LinearLayout.LayoutParams(0, dp(48), 1f);
        halfButton.setMargins(0, 0, dp(6), 0);
        quickRow.addView(youtubeButton, halfButton);

        LinearLayout.LayoutParams otherHalf =
                new LinearLayout.LayoutParams(0, dp(48), 1f);
        otherHalf.setMargins(dp(6), 0, 0, 0);
        quickRow.addView(whatsappButton, otherHalf);

        root.addView(quickRow, matchWrap());

        youtubeButton.setOnClickListener(v ->
                openApp("com.google.android.youtube", "YouTube")
        );

        whatsappButton.setOnClickListener(v ->
                openApp("com.whatsapp", "WhatsApp")
        );

        addSpace(root, 26);

        resultText = text(
                "ULTRON interface initialized. AI connection is not configured yet.",
                14, whiteColor, false
        );
        resultText.setGravity(Gravity.CENTER);
        root.addView(resultText, matchWrap());

        setContentView(scrollView);
    }

    private void startVoiceInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Speak a command for ULTRON"
        );

        try {
            startActivityForResult(intent, SPEECH_REQUEST);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No speech recognition service is available.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SPEECH_REQUEST && resultCode == RESULT_OK
                && data != null) {

            ArrayList<String> matches =
                    data.getStringArrayListExtra(
                            RecognizerIntent.EXTRA_RESULTS
                    );

            if (matches != null && !matches.isEmpty()) {
                String spokenCommand = matches.get(0);
                commandInput.setText(spokenCommand);
                handleCommand(spokenCommand);
            }
        }
    }

    private void handleCommand(String command) {
        String normalized = command.trim().toLowerCase(Locale.ROOT);

        if (normalized.isEmpty()) {
            resultText.setText("Please enter or speak a command.");
            return;
        }

        if (normalized.equals("youtube")
                || normalized.contains("open youtube")) {
            openApp("com.google.android.youtube", "YouTube");
            return;
        }

        if (normalized.equals("whatsapp")
                || normalized.contains("open whatsapp")) {
            openApp("com.whatsapp", "WhatsApp");
            return;
        }

        connectionText.setText("COMMAND RECEIVED");
        resultText.setText(
                "I received: \"" + command + "\"\n\n" +
                "AI chat and more phone controls will be added in the next stages."
        );
    }

    private void openApp(String packageName, String appName) {
        Intent launchIntent =
                getPackageManager().getLaunchIntentForPackage(packageName);

        if (launchIntent == null) {
            resultText.setText(
                    appName + " is not installed or cannot be opened."
            );
            return;
        }

        try {
            startActivity(launchIntent);
            resultText.setText("Opening " + appName + "...");
            connectionText.setText("COMMAND EXECUTED");
        } catch (Exception e) {
            resultText.setText("Could not open " + appName + ".");
        }
    }

    private Button makeButton(
            String label, int background, int foreground
    ) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(foreground);
        button.setAllCaps(false);

        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(14));
        button.setBackground(shape);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));

        return button;
    }

    private TextView text(
            String value, float size, int color, boolean bold
    ) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchButton() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );
    }

    private void addSpace(LinearLayout layout, int height) {
        TextView spacer = new TextView(this);
        layout.addView(
                spacer,
                new LinearLayout.LayoutParams(
                        1, dp(height)
                )
        );
    }

    private int dp(float value) {
        return (int) (value *
                getResources().getDisplayMetrics().density + 0.5f);
    }
}
