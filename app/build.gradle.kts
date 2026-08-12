plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.venompool888.capsulesms"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.venompool888.capsulesms"
        minSdk = 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        disable += setOf("AndroidGradlePluginVersion", "OldTargetApi")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
