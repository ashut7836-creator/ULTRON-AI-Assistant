package com.ultron.assistant;

import android.app.Application;
import android.util.Log;

import com.google.firebase.FirebaseApp;

/** Initializes Firebase and App Check before the Gemini client is used. */
public final class UltronApplication extends Application {

    private static final String TAG = "ULTRON";

    @Override
    public void onCreate() {
        super.onCreate();

        FirebaseApp firebaseApp = FirebaseApp.initializeApp(this);
        if (firebaseApp == null) {
            Log.i(TAG, "Firebase is not configured yet; Gemini will remain disabled.");
            return;
        }

        try {
            AppCheckInitializer.install();
        } catch (Exception error) {
            Log.w(TAG, "App Check initialization failed.", error);
        }
    }
}
