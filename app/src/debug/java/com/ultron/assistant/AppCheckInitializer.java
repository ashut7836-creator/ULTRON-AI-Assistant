package com.ultron.assistant;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;

/** Debug-only App Check provider. Never use this provider in a production build. */
final class AppCheckInitializer {

    private AppCheckInitializer() {}

    static void install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance());
    }
}
