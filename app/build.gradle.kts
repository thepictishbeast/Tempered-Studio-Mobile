plugins { id("com.android.application") }

android {
    namespace = "studio.tempered.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "studio.tempered.mobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
