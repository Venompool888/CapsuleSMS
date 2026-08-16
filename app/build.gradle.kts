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
        versionCode = 3
        versionName = "0.3.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        disable += setOf("AndroidGradlePluginVersion", "OldTargetApi")
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.klinkerapps:android-smsmms:5.2.6") {
        isTransitive = false
    }
    implementation("com.klinkerapps:logger:1.0.3")
    testImplementation("junit:junit:4.13.2")
}
