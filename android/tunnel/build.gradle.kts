plugins {
    alias(libs.plugins.android.library)
}

// The Go library hard-codes its UAPI socket path under the app's data directory.
val appId = "io.github.artemimennoi.awgbar"

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig { minSdk = 29 }
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
                    arguments("-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}")
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
