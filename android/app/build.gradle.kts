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
        versionCode = 3
        versionName = "0.1.2"
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

    // One APK per ABI keeps downloads small; the universal one is for "just install it".
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
