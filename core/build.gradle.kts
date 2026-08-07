import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    kotlin("android")
}

setupCore()

// Bundled VLM model (InternVL3-2B Q4_K_M + Q8_0 mmproj, ~1.4GB): downloaded
// from ModelScope at build time, cached under build/, and packaged as APK
// assets so L3 works out of the box. Chosen by the 2026-08 vlm-bench grounding
// benchmark (16/20 hits @896px input, smallest capable model). Kept out of
// git; assets are declared noCompress so AssetManager.openFd works (required
// for zero-copy mmap by llama.cpp).
abstract class DownloadBundledModel : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val base =
            "https://modelscope.cn/models/ggml-org/InternVL3-2B-Instruct-GGUF/resolve/master"
        val files = mapOf(
            "InternVL3-2B-Instruct-Q4_K_M.gguf" to 1116758816L,
            "mmproj-InternVL3-2B-Instruct-Q8_0.gguf" to 337012000L,
        )
        val dir = outputDir.get().asFile.resolve("bundled_model")
        dir.mkdirs()
        // Drop leftovers from a previously bundled model so they don't get
        // packaged alongside the current one.
        dir.listFiles()?.filter { it.name !in files.keys }?.forEach { it.delete() }
        files.forEach { (name, expectedSize) ->
            val target = dir.resolve(name)
            if (target.exists() && target.length() == expectedSize) return@forEach
            val url = "$base/$name"
            // JVM-direct TLS to modelscope gets reset on this network; curl
            // works. --noproxy keeps it off the local proxy (which can't reach
            // modelscope), -C - resumes partial downloads across retries.
            repeat(5) { attempt ->
                logger.lifecycle("downloadBundledModel: $url (attempt ${attempt + 1})")
                val rc = ProcessBuilder(
                    "curl", "-sL", "--noproxy", "*", "-C", "-", "-o", target.absolutePath, url,
                ).redirectErrorStream(true).start().waitFor()
                if (rc == 0 && target.length() == expectedSize) return@forEach
                logger.lifecycle("downloadBundledModel: attempt failed (rc=$rc, " +
                    "size=${if (target.exists()) target.length() else -1})")
            }
            target.delete()
            throw GradleException("downloadBundledModel: failed to fetch $name")
        }
    }
}

val downloadBundledModel = tasks.register<DownloadBundledModel>("downloadBundledModel")

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

// Register the downloaded model as generated assets for every variant.
extensions.getByType(LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(
        downloadBundledModel,
        DownloadBundledModel::outputDir,
    )
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
