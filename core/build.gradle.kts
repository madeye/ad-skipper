plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    kotlin("android")
}

setupCore()

android {
    namespace = "com.adskipper.core"

    defaultConfig {
        externalNativeBuild {
            cmake {
                abiFilters += "arm64-v8a"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }

        ksp {
            arg("room.incremental", "true")
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    aaptOptions {
        noCompress += "gguf"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.mlkit.text.recognition.chinese)
    api(libs.okhttp)
    api(libs.timber)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
