import org.gradle.api.GradleException
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

val releaseSigningPropertyNames = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val hasReleaseSigningProperties =
    releaseSigningPropertyNames.all { keystoreProperties.containsKey(it) }

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Verifies release signing properties are present and the keystore exists."
    doLast {
        val missing =
            releaseSigningPropertyNames.filterNot { keystoreProperties.containsKey(it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing ${missing.joinToString()} in keystore.properties; " +
                    "release builds are unsigned without them.",
            )
        }
        val storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
        if (!storeFile.isFile) {
            throw GradleException("Release keystore not found: ${storeFile.path}")
        }
    }
}

tasks.configureEach {
    if (
        name.endsWith("Release") &&
        (name.startsWith("assemble") ||
            name.startsWith("package") ||
            name == "bundleRelease" ||
            name == "installRelease")
    ) {
        dependsOn(verifyReleaseSigning)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.ktfmt.gradle)
}

android {
    namespace = "com.prfd.tinytuya"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.prfd.tinytuya"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.prfd.tinytuya.ManualTestProbe"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    if (hasReleaseSigningProperties) {
        signingConfigs {
            create("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        }
    }
    buildTypes {
        release {
            if (hasReleaseSigningProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}


kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xconsistent-data-class-copy-visibility")
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // Keep the Python/Android boundary reproducible. Upgrade deliberately
            // after running the bridge and real-device compatibility checks.
            install("tinytuya==1.20.0")
        }
    }
}

ktfmt {
    googleStyle()
    maxWidth.set(100)
    removeUnusedImports.set(false)
}

dependencies {
    implementation(project(":device-core"))
    implementation(project(":device-profiles"))
    implementation(project(":device-ui"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
