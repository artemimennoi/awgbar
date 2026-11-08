plugins {
    alias(libs.plugins.android.library)
}

// The Go library hard-codes its UAPI socket path under the app's data directory.
val appId = "io.github.artemimennoi.awgbar"

// The tunnel library: awgcore (Rust, the default) or the amneziawg-go fallback, -PawgCore=go.
// -PawgcoreSrc=<checkout> builds awgcore from a local checkout instead of the pinned revision.
val awgCore = providers.gradleProperty("awgCore").getOrElse("awgcore")
val awgcoreSrc = providers.gradleProperty("awgcoreSrc").getOrElse("")
require(awgCore == "awgcore" || awgCore == "go") { "awgCore must be awgcore or go, not $awgCore" }

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 29
        // The app ships for 64-bit ARM only; building the library for other ABIs is wasted time.
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake { path("tools/CMakeLists.txt") }
    }
    buildTypes {
        all {
            externalNativeBuild {
                cmake {
                    targets("libwg-go.so")
                    arguments(
                        "-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}",
                        "-DAWG_CORE=$awgCore",
                        "-DAWGCORE_SRC=$awgcoreSrc",
                    )
                }
            }
        }
        release {
            externalNativeBuild { cmake { arguments("-DANDROID_PACKAGE_NAME=$appId") } }
        }
        debug {
            externalNativeBuild { cmake { arguments("-DANDROID_PACKAGE_NAME=$appId.debug") } }
        }
    }
    lint { disable += listOf("LongLogTag", "NewApi") }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    compileOnly(libs.jsr305)
    testImplementation(libs.junit)
}
