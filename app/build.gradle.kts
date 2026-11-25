import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
}

googleServices {
    // Keep the starter build usable before the owner's Firebase config is added.
    missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN
}

android {
    namespace = "com.ultron.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ultron.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")

    // Firebase AI Logic provides a mobile-safe Gemini connection protected by App Check.
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-ai")
    // Firebase function-call arguments and responses use Kotlin serialization JSON types.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.13.0")
    implementation("com.google.guava:guava:31.0.1-android")

    // Development builds use the debug provider; release builds use Play Integrity.
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    releaseImplementation("com.google.firebase:firebase-appcheck-playintegrity")
}
