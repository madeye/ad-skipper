plugins {
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.compose) apply false
}

buildscript {
    repositories {
        // Official repos first; Aliyun mirrors are kept as a CN fallback but
        // are flaky for freshly-released artifacts (saw 502s for KSP).
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
    }

    dependencies {
        classpath(libs.android.gradle)
        classpath(libs.kotlin.gradle)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/public")
    }
}
