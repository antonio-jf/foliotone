import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release builds (CI) pass -PversionCode/-PversionName; local builds use VERSION with code 1.
val baseVersion = rootProject.file("VERSION").readText().trim()
// Signing key, never in the repository: GitHub Actions passes it through the environment; on a Mac,
// keystore.properties (made by tools/create-release-key.sh, ignored by git) points to it. With either,
// debug and release builds share the release key, so Android Studio builds install over releases.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signing(env: String, property: String): String? = System.getenv(env) ?: keystoreProperties.getProperty(property)
val releaseKeystore: String? = signing("FOLIOTONE_KEYSTORE", "storeFile")

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
            storePassword = signing("FOLIOTONE_KEYSTORE_PASSWORD", "storePassword")
            keyAlias = signing("FOLIOTONE_KEY_ALIAS", "keyAlias")
            keyPassword = signing("FOLIOTONE_KEY_PASSWORD", "keyPassword")
        }
    }
    buildTypes {
        val releaseSigning = signingConfigs.findByName("release")
        getByName("debug") { if (releaseSigning != null) signingConfig = releaseSigning }
        getByName("release") {
            isMinifyEnabled = false
            if (releaseSigning != null) signingConfig = releaseSigning
        }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jsoup:jsoup:1.18.3")
}
