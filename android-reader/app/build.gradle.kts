import java.net.URI

val sherpaVersion = "1.12.21"

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.voicereader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.voicereader"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
        resourceConfigurations += listOf("ru", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // Модель читается нативным кодом как обычный файл, сжимать её в APK незачем.
        noCompress += listOf("onnx")
    }
    splits {
        abi {
            isEnable = true
            reset()
            // Нативные библиотеки onnxruntime весят много, поэтому по APK на архитектуру.
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Offline PDF text extraction
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Нейросетевой синтез речи, работает локально (см. задачу fetchTtsAssets)
    implementation(files("libs/sherpa-onnx-$sherpaVersion.aar"))

    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------
// Нейросетевые голоса и библиотека синтеза не хранятся в репозитории:
// модели весят по 63 МБ, поэтому подтягиваются один раз при сборке.
// На работу приложения это не влияет — всё уезжает внутрь APK и работает офлайн.
// ---------------------------------------------------------------------------

data class VoiceAsset(val dir: String, val repo: String, val model: String, val bytes: Long)

val neuralVoices = listOf(
    VoiceAsset("irina", "csukuangfj/vits-piper-ru_RU-irina-medium", "ru_RU-irina-medium.onnx", 63_201_422L),
    VoiceAsset("denis", "csukuangfj/vits-piper-ru_RU-denis-medium", "ru_RU-denis-medium.onnx", 63_201_422L),
    VoiceAsset("ruslan", "csukuangfj/vits-piper-ru_RU-ruslan-medium", "ru_RU-ruslan-medium.onnx", 63_201_422L)
)

fun downloadIfNeeded(url: String, target: File, expectedBytes: Long?) {
    if (target.exists() && (expectedBytes == null || target.length() == expectedBytes)) return
    target.parentFile.mkdirs()
    val part = File(target.parentFile, target.name + ".part")
    logger.lifecycle("Загружаю ${target.name} …")
    URI(url).toURL().openStream().use { input ->
        part.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
    }
    if (expectedBytes != null && part.length() != expectedBytes) {
        part.delete()
        throw GradleException("Файл ${target.name} скачан не полностью, повторите сборку")
    }
    part.renameTo(target)
}

val fetchTtsAssets = tasks.register("fetchTtsAssets") {
    description = "Скачивает нейросетевые голоса и AAR sherpa-onnx, распаковывает данные espeak-ng"
    group = "build setup"

    val assetsRoot = layout.projectDirectory.dir("src/main/assets/tts").asFile
    val libsRoot = layout.projectDirectory.dir("libs").asFile
    val espeakZip = rootProject.file("tts-assets/espeak-ng-data.zip")
    val aarFile = File(libsRoot, "sherpa-onnx-$sherpaVersion.aar")
    val voices = neuralVoices

    doLast {
        downloadIfNeeded(
            "https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/main/android/aar/sherpa-onnx-$sherpaVersion.aar",
            aarFile,
            null
        )

        val espeakDir = File(assetsRoot, "espeak-ng-data")
        if (!File(espeakDir, "phontab").exists()) {
            espeakDir.deleteRecursively()
            copy {
                from(zipTree(espeakZip))
                into(assetsRoot)
            }
        }

        voices.forEach { voice ->
            val dir = File(assetsRoot, voice.dir)
            downloadIfNeeded(
                "https://huggingface.co/${voice.repo}/resolve/main/${voice.model}",
                File(dir, voice.model),
                voice.bytes
            )
            downloadIfNeeded(
                "https://huggingface.co/${voice.repo}/resolve/main/tokens.txt",
                File(dir, "tokens.txt"),
                null
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(fetchTtsAssets) }
