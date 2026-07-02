plugins { id("com.android.application") }

android {
    namespace = "studio.tempered.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "studio.tempered.mobile"
        minSdk = 24
        targetSdk = 34
        // MUST be bumped with every release tag (versionCode = MAJOR*10000 +
        // MINOR*100 + PATCH). These were stuck at 1/"0.1.0" through v0.2–v0.3.13,
        // so every installed APK reported itself as 0.1.0 — Obtainium therefore
        // saw "v0.1 installed" forever and update detection never converged.
        versionCode = 10314
        versionName = "0.3.14"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
