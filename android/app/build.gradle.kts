import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Internal testing only. Do not embed credentials or enable public API access from an APK.
val enrichmentApiBaseUrl = providers.gradleProperty("chargewatchApiBaseUrl").orElse("").get()
require(enrichmentApiBaseUrl.isBlank() || Regex("""https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?/?""").matches(enrichmentApiBaseUrl)) {
    "chargewatchApiBaseUrl must be an HTTPS origin without path or credentials"
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "nl.vanhinthem.chargewatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.vanhinthem.chargewatch"
        minSdk = 26
        targetSdk = 36
        // CI run numbers increase monotonically across PR builds for install-over-update.
        versionCode = 100000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "0.1.0"
        buildConfigField("String", "CHARGEWATCH_API_BASE_URL", "\"$enrichmentApiBaseUrl\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Public development-only signing key: never reuse for production releases.
            storeFile = rootProject.file("debug-signing/chargewatch-dev-debug.keystore")
            storePassword = "chargewatch-debug-only"
            keyAlias = "chargewatch-debug"
            keyPassword = "chargewatch-debug-only"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation("org.maplibre.gl:android-sdk:13.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
