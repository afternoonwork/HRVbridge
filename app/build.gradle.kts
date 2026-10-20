plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Placeholder: the package ID is permanent on Google Play, replace before the first upload.
    namespace = "com.placeholder.hrvbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.placeholder.hrvbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Robolectric tests read the debug assets (test vectors).
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric on JDK 17+ with SDK 36 reads FileDescriptor internals.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }

    sourceSets {
        // The simulated strap replays the shared test vectors (debug builds only).
        getByName("debug").assets.directories.add("../core/src/test/resources")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
