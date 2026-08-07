pluginManagement {
    repositories {
        // Note: Aliyun's gradle-plugin mirror serves empty metadata for KSP —
        // keep the official plugin repos here (verified reachable).
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":core", ":mobile")
