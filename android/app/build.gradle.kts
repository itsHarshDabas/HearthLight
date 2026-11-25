import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional fixed signing key so new APKs install over old ones (keeps chats). In CI it comes from the
// HEARTHLIGHT_KEYSTORE_BASE64 / HEARTHLIGHT_KEYSTORE_PASSWORD repository secrets; without them the
// Android debug key is used, which differs per machine.
val sideloadKeystore = System.getenv("HEARTHLIGHT_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "dev.localai.chat"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.localai.chat"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (sideloadKeystore != null) {
            create("sideload") {
                storeFile = sideloadKeystore
                storePassword = System.getenv("HEARTHLIGHT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HEARTHLIGHT_KEY_ALIAS") ?: "hearthlight"
                keyPassword = System.getenv("HEARTHLIGHT_KEY_PASSWORD") ?: System.getenv("HEARTHLIGHT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = signing
        }
        release {
            isMinifyEnabled = false
            // Sideload-friendly: signed with the fixed key above, or the debug key.
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("dev.localai:core:0.1.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
