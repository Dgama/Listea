plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.listea"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.listea"
        minSdk = 24
        targetSdk = 37
        // The version is the round the code is at — the same name the iteration docs and the git
        // tag use. versionCode is the numeric part flattened to one integer, so it cannot drift
        // from it. A suffix names a step on the same line and keeps that line's code: 0.8.2f still
        // installs over 0.8.2, and the 0.8.3 preview (803) still installs over both. The hyphen
        // suffix says the sub-version is not frozen yet; the numeric prefix is what the installer
        // and the About page measure.
        val version = "0.8.3-preview"
        versionName = version
        versionCode = version.takeWhile { it.isDigit() || it == '.' }
            .split(".")
            .let { it[0].toInt() * 10_000 + it[1].toInt() * 100 + it[2].toInt() }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // The About page names the build it is: the version comes from the same `version` this
        // file derives versionCode from, so there is no second place for it to drift.
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

ksp {
    // Room schema history, checked in so migrations can be tested against the real generated
    // shape of each version rather than reconstructed from memory.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.nucleus.pdfium)
    implementation(libs.coil.gif)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}