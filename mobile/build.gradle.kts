plugins {
    id("com.android.application")
    kotlin("android")
    alias(libs.plugins.compose)
}

setupApp()

android {
    namespace = "com.adskipper"

    defaultConfig {
        applicationId = "com.tangzixiang.adskipper"
    }

    buildFeatures.compose = true

    // GGUF assets must stay uncompressed so AssetManager.openFd works
    // (noCompress in a library module does not propagate to packaging).
    aaptOptions {
        noCompress += "gguf"
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.coil.compose)
}
