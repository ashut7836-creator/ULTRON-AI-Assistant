plugins {
    id("com.android.application")
}

android {
    namespace = "com.ultron.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ultron.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
}
