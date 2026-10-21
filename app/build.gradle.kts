plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Internal code namespace; the Play package ID is applicationId (permanent on Google Play).
    namespace = "com.placeholder.hrvbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "fyi.paulmuller.signon"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        // The release tag must be v<versionName>; bump versionCode with every release.
        versionName = "0.1.0-beta.2"
    }

    // Release signing (upload key) from the environment or ~/.gradle/gradle.properties,
    // never from the repo. Without all four values release builds stay unsigned (CI).
    val signingValues = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD").associateWith { name ->
        providers.environmentVariable("HRVBRIDGE_$name")
            .orElse(providers.gradleProperty("hrvbridge." + name.lowercase().replace('_', '.')))
            .orNull
    }
    val canSign = signingValues.values.all { !it.isNullOrBlank() }
    if (canSign) {
        signingConfigs {
            create("release") {
                storeFile = file(signingValues.getValue("KEYSTORE_PATH")!!)
                storePassword = signingValues.getValue("KEYSTORE_PASSWORD")
                keyAlias = signingValues.getValue("KEY_ALIAS")
                keyPassword = signingValues.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // shortcut: R8 off because the release build has never run on a device, turn on once a release APK has been smoke tested on a phone.
            isMinifyEnabled = false
            if (canSign) signingConfig = signingConfigs.getByName("release")
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
