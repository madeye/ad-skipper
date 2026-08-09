import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.BaseExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

private val Project.android get() = extensions.getByName<BaseExtension>("android")
private val BaseExtension.lint get() = (this as CommonExtension<*, *, *, *, *, *>).lint

fun Project.setupCommon() {
    val javaVersion = JavaVersion.VERSION_17
    android.apply {
        compileSdkVersion(36)
        defaultConfig {
            // AccessibilityService.takeScreenshot requires API 30.
            minSdk = 30
            targetSdk = 36
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        compileOptions {
            sourceCompatibility = javaVersion
            targetCompatibility = javaVersion
        }
        lint.apply {
            abortOnError = true
            warningsAsErrors = false
        }
    }
    extensions.getByName<KotlinAndroidProjectExtension>("kotlin").compilerOptions.jvmTarget
        .set(JvmTarget.fromTarget(javaVersion.toString()))
}

fun Project.setupCore() {
    setupCommon()
    android.apply {
        defaultConfig {
            // Build timestamp as yyyyMMddHH; fits in Play's 2100000000
            // versionCode ceiling until year 2100.
            versionCode = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHH"))
                .toInt()
            versionName = "1.3"
        }
        buildFeatures.buildConfig = true
    }
}

fun Project.setupApp() {
    setupCore()

    android.apply {
        // Release signing from local.properties (never committed):
        // KEYSTORE_PATH / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD.
        // Absent keys (e.g. CI) leave the release build unsigned.
        val props = java.util.Properties().apply {
            val f = project.rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use(::load)
        }
        val keystorePath = props.getProperty("KEYSTORE_PATH")
        if (keystorePath != null) {
            signingConfigs.create("release") {
                storeFile = project.file(keystorePath)
                storePassword = props.getProperty("KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("KEY_ALIAS")
                keyPassword = props.getProperty("KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("debug") {
                isPseudoLocalesEnabled = true
            }
            getByName("release") {
                isShrinkResources = true
                isMinifyEnabled = true
                proguardFile(getDefaultProguardFile("proguard-android.txt"))
                proguardFile("proguard-rules.pro")
                signingConfigs.findByName("release")?.let { signingConfig = it }
            }
        }
        packagingOptions.jniLibs.useLegacyPackaging = true
        splits.abi {
            isEnable = false
        }
    }

    dependencies.add("implementation", project(":core"))
}
