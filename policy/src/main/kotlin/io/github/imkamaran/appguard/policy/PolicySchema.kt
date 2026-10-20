package io.github.imkamaran.appguard.policy

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Companion-app appearance; never published to hooked processes. */
@Serializable
data class UiSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Material You wallpaper colors on Android 12+. */
    val dynamicColor: Boolean = true,
)

/** The companion app's canonical, private copy of the whole configuration. */
@Serializable
data class ConfigState(
    val schema: Int = PolicySchema.SCHEMA_VERSION,
    val global: GlobalSettings = GlobalSettings(),
    val policies: Map<String, AppPolicy> = emptyMap(),
    val ui: UiSettings = UiSettings(),
    /** User-defined templates; built-in ones live in Templates.BUILT_IN and are never stored. */
    val templates: List<PolicyTemplate> = emptyList(),
)

/**
 * Layout of the preference file shared between the companion app (writer) and hooked processes
 * (readers, through LSPosed's XSharedPreferences).
 *
 * ```
 * schema            Int     SCHEMA_VERSION of the writer
 * global            String  GlobalSettings JSON
 * p:<packageName>   String  AppPolicy JSON, one record per package
 * ```
 *
 * Versioning: readers ignore unknown JSON keys and fall back to defaults for missing ones, so
 * additive changes keep the same version. A breaking change bumps [SCHEMA_VERSION]; a reader that
 * finds a newer version than it understands applies nothing (fails open).
 */
object PolicySchema {
    const val MODULE_PACKAGE = "io.github.imkamaran.appguard"
    const val PREFS_NAME = "policies"
    const val SCHEMA_VERSION = 1

    const val KEY_SCHEMA = "schema"
    const val KEY_GLOBAL = "global"
    const val POLICY_KEY_PREFIX = "p:"

    /** Upper bound on a stored record; anything larger is treated as corrupt. */
    const val MAX_RECORD_CHARS = 16 * 1024

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun policyKey(packageName: String): String = POLICY_KEY_PREFIX + packageName

    fun isValidPackageName(name: String): Boolean = name.length <= 255 && PACKAGE_NAME.matches(name)

    fun isSupportedSchema(stored: Int): Boolean = stored in 1..SCHEMA_VERSION

    fun encodePolicy(policy: AppPolicy): String = json.encodeToString(AppPolicy.serializer(), policy)

    fun encodeGlobal(settings: GlobalSettings): String = json.encodeToString(GlobalSettings.serializer(), settings)

    /**
     * Decodes the record stored under [policyKey] ([expectedPackage]). Returns null for missing,
     * oversized, malformed records, and for records whose embedded package name does not match the
     * key; callers treat null as "no policy" (fail open).
     */
    fun decodePolicy(expectedPackage: String, raw: String?): AppPolicy? {
        if (raw.isNullOrEmpty() || raw.length > MAX_RECORD_CHARS) return null
        val policy = try {
            json.decodeFromString(AppPolicy.serializer(), raw)
        } catch (_: SerializationException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
        return policy.takeIf { it.packageName == expectedPackage }
    }

    /** Returns defaults for missing data; returns null only when the data exists but is corrupt. */
    fun decodeGlobal(raw: String?): GlobalSettings? {
        if (raw.isNullOrEmpty()) return GlobalSettings()
        if (raw.length > MAX_RECORD_CHARS) return null
        return try {
            sanitize(json.decodeFromString(GlobalSettings.serializer(), raw))
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun sanitize(settings: GlobalSettings): GlobalSettings = settings.copy(
        retentionDays = settings.retentionDays.coerceIn(GlobalSettings.MIN_RETENTION_DAYS, GlobalSettings.MAX_RETENTION_DAYS),
        maxEvents = settings.maxEvents.coerceIn(GlobalSettings.MIN_MAX_EVENTS, GlobalSettings.MAX_MAX_EVENTS),
    )
}
