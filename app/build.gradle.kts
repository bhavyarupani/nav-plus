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
        // Bumped from 24: GraphHopper's storage layer (RAMDataAccess) uses MethodHandle
        // invocation, which D8 only supports from API 26 (Android O, 2017) - confirmed this is
        // not version-specific to GraphHopper 10.x (9.1 has the same bytecode pattern). See
        // ZERO_COST_ARCHITECTURE.md for the full routing-engine decision record.
        minSdk = 26
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

    packaging {
        // GraphHopper 7.0 pulls in jakarta.xml.bind-api and jakarta.activation-api, which both
        // ship an identical META-INF/LICENSE.md - neither file affects app behaviour, so drop
        // the duplicate rather than picking one arbitrarily.
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/NOTICE.txt"
        }
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
    // Symbol/line annotation managers (SymbolManager/LineManager) - the closest MapLibre
    // equivalent to GoogleMap's addMarker/addPolyline, used by MapLibreMapController to replace
    // those call sites across MainActivity/NavigationActivity/RoadPulseNavigationScreen.
    implementation("org.maplibre.gl:android-plugin-annotation-v9:3.0.2")
    // Pinned to 7.0, not the latest 10.x: from 8.x onward GraphHopper's routing weighting is
    // computed via CustomModel expressions compiled at runtime with Janino, which doesn't work
    // on Android's ART/DEX runtime (confirmed via a real on-device NoSuchMethodError - this is a
    // hard incompatibility, not a config issue). 7.0's simple named-weighting API
    // (Profile.setVehicle/setWeighting) uses precompiled Java classes (e.g. FastestWeighting)
    // instead and has no Janino dependency in its actual code path. See
    // ZERO_COST_ARCHITECTURE.md for the full decision record.
    implementation("com.graphhopper:graphhopper-core:7.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

secrets {
    defaultPropertiesFileName = "local.defaults.properties"
}
