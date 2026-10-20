import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release key: keystore.properties at the repo root (never committed). Without it, release
// builds fall back to the local debug key so anyone can build.
val releaseKey = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "io.github.imkamaran.appguard"
    compileSdk = 36

    defaultConfig {
        // Must equal PolicySchema.MODULE_PACKAGE: hooked processes locate the shared preferences by it.
        applicationId = "io.github.imkamaran.appguard"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.5.0"
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Hook entry points are looked up by name from assets/xposed_init; shrinking is not
            // worth the risk for a module of this size.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":policy"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Provided by LSPosed/Vector at runtime; must not be packaged.
    compileOnly(libs.xposed.api)

    testImplementation(libs.junit)
}
