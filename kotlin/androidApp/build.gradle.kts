plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

val defaultServerUrl = providers.gradleProperty("SMART_POT_SERVER_URL").orElse("http://103.236.87.90:18080")
val demoToken = providers.gradleProperty("SMART_POT_DEMO_TOKEN")
    .orElse(providers.environmentVariable("SMART_POT_DEMO_TOKEN"))
    .orElse("smart-pot-demo-token")

fun String.asBuildConfigString() = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun String.requireBearerToken68(): String = trim().also { value ->
    require(value.matches(Regex("[A-Za-z0-9._~+/=-]+"))) {
        "SMART_POT_DEMO_TOKEN must be a Bearer token68 value. Check that the shell variable was expanded before building."
    }
}

android {
    namespace = "com.fu1fan.smartpot"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.fu1fan.smartpot"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_SERVER_URL", defaultServerUrl.get().trimEnd('/').asBuildConfigString())
        buildConfigField("String", "DEMO_TOKEN", demoToken.get().requireBearerToken68().asBuildConfigString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared-protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.compose.ui.tooling)
}
