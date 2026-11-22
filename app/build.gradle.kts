plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "pl.lk.papermind"
    compileSdk = 37

    defaultConfig {
        applicationId = "pl.lk.papermind"
        minSdk = 31          // Gemini Nano (AICore) wymaga Androida 12+; S25U ma 15/16
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
