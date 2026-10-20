plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.imkamaran.agprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.imkamaran.agprobe"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // "target" gets a policy during verification; "control" stays in scope without one, proving
    // that apps without a policy keep their original behavior.
    flavorDimensions += "role"
    productFlavors {
        create("target") { dimension = "role" }
        create("control") {
            dimension = "role"
            applicationIdSuffix = ".control"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}
