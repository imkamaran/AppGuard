package io.github.imkamaran.appguard

/**
 * Set by the hook runtime when it loads into AppGuard's own process (see XposedEntry). A static
 * field is used instead of hooking a method so ART cannot inline the "inactive" answer.
 */
object ModuleStatus {
    /** Xposed API version reported by the framework, or 0 when the module is not active. */
    @JvmField
    @Volatile
    var hookApiVersion: Int = 0

    val isActive: Boolean get() = hookApiVersion > 0
}
