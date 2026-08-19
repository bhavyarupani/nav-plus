import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) localPropsFile.inputStream().use { localProps.load(it) }

android {
    namespace   = "com.navplus.app"
    compileSdk  = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.navplus.app"
        minSdk        = libs.versions.minSdk.get().toInt()
        targetSdk     = libs.versions.targetSdk.get().toInt()
        versionCode   = 1
        versionName   = "0.1.0"

        buildConfigField("String", "TOMTOM_API_KEY", "\"${localProps.getProperty("TOMTOM_API_KEY", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/**"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    implementation(project(":core:common"))
    implementation(project(":core:connectivity"))
    implementation(project(":core:map"))
    implementation(project(":core:routing"))
    implementation(project(":core:navigation"))
    implementation(project(":core:safety"))
    implementation(project(":core:search"))
    implementation(project(":core:regions"))
    implementation(project(":core:group"))
    implementation(project(":feature:home"))
    implementation(project(":feature:navigation"))
    implementation(project(":feature:search"))
    implementation(project(":feature:group"))
    implementation(project(":feature:regions"))

    debugImplementation(libs.compose.ui.tooling)
}
