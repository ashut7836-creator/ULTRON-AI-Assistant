package com.ultron.assistant;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

/** Production App Check provider. Configure Play Integrity in Firebase before release. */
final class AppCheckInitializer {

    private AppCheckInitializer() {}

    static void install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance());
    }
}
