pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://api.xposed.info/") {
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "AppGuard"

// Pure JVM: policy models, validation, schema, synthetic values, audit formats.
include(":policy")
// Companion app + LSPosed hook runtime (both must live in the module APK).
include(":app")
// Probe app that exercises each hooked API; used for on-device verification.
include(":testapp")
