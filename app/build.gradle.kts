plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release builds (CI) pass -PversionCode/-PversionName; local builds use VERSION with code 1.
val baseVersion = rootProject.file("VERSION").readText().trim()
// Release signing comes from the environment, so no key or password is ever in the repository.
val releaseKeystore: String? = System.getenv("FOLIOTONE_KEYSTORE")

android {
    namespace = "com.linguareader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.linguareader"
        minSdk = 26
        targetSdk = 35
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: baseVersion
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("FOLIOTONE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FOLIOTONE_KEY_ALIAS")
            keyPassword = System.getenv("FOLIOTONE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
