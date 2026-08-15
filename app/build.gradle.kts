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
        versionCode = 2
        versionName = "0.2.0"
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
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
