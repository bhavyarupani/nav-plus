plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.roadpulse.auto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.roadpulse.auto"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configurations.configureEach {
    exclude(group = "com.google.android.gms", module = "play-services-maps")
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("com.google.android.libraries.navigation:navigation:7.6.1")
    implementation("com.google.android.libraries.places:places:5.3.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Free-stack migration proof of concept - see ZERO_COST_ARCHITECTURE.md. Not wired into any
    // app screen yet; added here to verify it resolves and builds against this project's actual
    // AGP/Kotlin/compileSdk versions before further migration work depends on it.
    implementation("org.maplibre.gl:android-sdk:11.11.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

secrets {
    defaultPropertiesFileName = "local.defaults.properties"
}
