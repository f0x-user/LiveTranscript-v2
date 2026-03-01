import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Download sherpa-onnx AAR if not present or too small
// Correct filename format: sherpa-onnx-{VERSION}.aar (no 'v' prefix, no '-android' suffix)
val sherpaVersion = "1.12.28"
val sherpaAar = file("${projectDir}/libs/sherpa-onnx-${sherpaVersion}.aar")

tasks.register("downloadSherpaOnnxAAR") {
    doLast {
        if (sherpaAar.length() < 10_000L) {
            sherpaAar.parentFile.mkdirs()
            val urls = listOf(
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${sherpaVersion}/sherpa-onnx-${sherpaVersion}.aar",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.27/sherpa-onnx-1.12.27.aar"
            )
            var downloaded = false
            for (url in urls) {
                try {
                    logger.lifecycle("Trying to download sherpa-onnx AAR from: $url")
                    URI(url).toURL().openStream().use { inp ->
                        sherpaAar.outputStream().use { out -> inp.copyTo(out) }
                    }
                    if (sherpaAar.length() > 10_000L) {
                        logger.lifecycle("Downloaded sherpa-onnx AAR: ${sherpaAar.length()} bytes")
                        downloaded = true
                        break
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to download from $url: ${e.message}")
                }
            }
            if (!downloaded) {
                logger.warn("Could not download sherpa-onnx AAR. Build may fail if AAR is missing.")
            }
        } else {
            logger.lifecycle("sherpa-onnx AAR already present: ${sherpaAar.length()} bytes")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn("downloadSherpaOnnxAAR")
}

android {
    namespace = "com.livetranscript"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livetranscript"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "models")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
