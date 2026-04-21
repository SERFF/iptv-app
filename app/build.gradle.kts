import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// TMDB bearer token is read from local.properties (git-ignored). If missing, the TMDB
// integration silently disables itself — the rest of the app keeps working.
val tmdbBearerToken: String = run {
    val props = Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use(props::load)
    props.getProperty("TMDB_BEARER_TOKEN", "")
}

android {
    namespace = "nl.vanvrouwerff.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "nl.vanvrouwerff.iptv"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "TMDB_BEARER_TOKEN", "\"$tmdbBearerToken\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 + resource shrinking shave significant size off the APK and remove a chunk
            // of dead code that would otherwise slow class loading on the Formuler. Rules
            // for serialization, Retrofit, Room, Media3, etc. live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.tv.foundation.ExperimentalTvFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.tv.foundation)
    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.datastore.preferences)

    implementation(libs.coil.compose)

    implementation(libs.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
