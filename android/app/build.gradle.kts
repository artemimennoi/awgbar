import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from android/keystore.properties or ~/.android/awgbar-release.properties,
// both outside git. Keep the key: Android only installs updates signed with the same one.
val signing = listOf(rootProject.file("keystore.properties"), File(System.getProperty("user.home"), ".android/awgbar-release.properties"))
    .firstOrNull { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "io.github.artemimennoi.awgbar"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "io.github.artemimennoi.awgbar"
        minSdk = 29
        targetSdk = 36
        // One version for all AWG Bar clients, in the repository's VERSION file;
        // x.y.z becomes versionCode x*10000 + y*100 + z.
        versionName = rootProject.file("../VERSION").readText().trim()
        versionCode = versionName!!.split(".").map(String::toInt).let { (x, y, z) -> x * 10000 + y * 100 + z }
        // 64-bit ARM only, the phones of the last years. 32-bit ARM and x86 (old phones, TV sticks,
        // Chromebooks, emulators on Intel) aren't supported; one APK, nothing to choose from.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signing != null) create("release") {
            storeFile = File(signing.getProperty("storeFile")).let { if (it.isAbsolute) it else rootProject.file(it) }
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":tunnel"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.android.embedded)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
