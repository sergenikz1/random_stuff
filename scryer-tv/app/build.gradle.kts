plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.scryertv.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.scryertv.client"
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        vectorDrawables.useSupportLibrary = true
    }

    // Релизная сборка подписывается ключом из переменных окружения (см. CI).
    // Без ключа release остаётся неподписанным: случайный debug-ключ ломает обновления.
    val keystorePath: String? = System.getenv("SCRYERTV_KEYSTORE_FILE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SCRYERTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SCRYERTV_KEY_ALIAS")
                keyPassword = System.getenv("SCRYERTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation(libs.okhttp)
    implementation(libs.glide)
    implementation(libs.kotlinx.coroutines.android)
}
