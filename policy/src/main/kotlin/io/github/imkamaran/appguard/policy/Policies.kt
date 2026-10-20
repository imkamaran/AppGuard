package io.github.imkamaran.appguard.policy

import kotlinx.serialization.Serializable

/**
 * How one policy category treats an app.
 *
 * - [DEFAULT]: inherit the category default from [GlobalSettings.defaults].
 * - [ALLOW]: never modify behavior (calls may still be audited).
 * - [RESTRICT]: apply the category's documented restriction at supported interception points.
 * - [SIMULATE]: return configured synthetic values at supported interception points.
 */
@Serializable
enum class Mode { DEFAULT, ALLOW, RESTRICT, SIMULATE }

@Serializable
enum class Category(val label: String) {
    LOCATION("Location"),
    CLIPBOARD("Clipboard"),
    IDENTIFIERS("Identifiers"),
    SENSORS("Sensors"),
    SYSTEM("System"),
    NETWORK("Network"),
    CONTACTS("Contacts & calendar"),
    TELEPHONY("Phone & SIM"),
}

@Serializable
data class LocationPolicy(
    val mode: Mode = Mode.DEFAULT,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    /** Horizontal accuracy in meters; null means [LocationSpec.DEFAULT_ACCURACY_M]. */
    val accuracyMeters: Float? = null,
    /** Altitude in meters above WGS84; null means "no altitude" on the synthetic fix. */
    val altitudeMeters: Double? = null,
) {
    companion object {
        val SUPPORTED_MODES = listOf(Mode.DEFAULT, Mode.ALLOW, Mode.RESTRICT, Mode.SIMULATE)
    }
}

@Serializable
data class ClipboardPolicy(
    val mode: Mode = Mode.DEFAULT,
) {
    companion object {
        val SUPPORTED_MODES = listOf(Mode.DEFAULT, Mode.ALLOW, Mode.RESTRICT)
    }
}

@Serializable
data class IdentifierPolicy(
    /** Settings.Secure.ANDROID_ID: ALLOW or SIMULATE. */
    val androidId: Mode = Mode.DEFAULT,
    /** android.os.Build manufacturer/brand/model/device/product: ALLOW or SIMULATE. */
    val deviceInfo: Mode = Mode.DEFAULT,
    val deviceProfile: String = DeviceProfiles.DEFAULT_ID,
    /**
     * Per-app random seed that synthetic identifiers are derived from. Rotating it produces a
     * new, still stable identity; the companion app creates it when the policy is first saved.
     */
    val seed: String = "",
) {
    companion object {
        val SUPPORTED_MODES = listOf(Mode.DEFAULT, Mode.ALLOW, Mode.SIMULATE)
    }
}

@Serializable
data class SensorPolicy(
    /** ALLOW = audit only (registrations are logged, data untouched); RESTRICT = hide [restrictedGroups]. */
    val mode: Mode = Mode.DEFAULT,
    val restrictedGroups: Set<String> = SensorGroups.DEFAULT_RESTRICTED,
) {
    companion object {
        val SUPPORTED_MODES = listOf(Mode.DEFAULT, Mode.ALLOW, Mode.RESTRICT)
    }
}

@Serializable
data class NetworkPolicy(
    /** RESTRICT = report "no Wi-Fi details"; SIMULATE = a stable synthetic network derived from the identity seed. */
    val mode: Mode = Mode.DEFAULT,
) {
    companion object {
        val SUPPORTED_MODES = listOf(Mode.DEFAULT, Mode.ALLOW, Mode.RESTRICT, Mode.SIMULATE)
    }
}

@Serializable
data class ContactsPolicy(
    /** RESTRICT = queries to [restrictedSources] return an empty result. */
    val mode: Mode = Mode.DEFAULT,
    val restrictedSources: Set<String> = ContactSources.DEFAULT_RESTRICTED,
) {
    companion object {
        val SUPPORTED_MODES = listOf(Mode.DEFAULT, Mode.ALLOW, Mode.RESTRICT)
    }
}

/** A personal-data content provider group, identified by its provider authorities. */
data class ContactSource(val id: String, val label: String, val description: String, val authorities: Set<String>)

object ContactSources {
    val ALL: List<ContactSource> = listOf(
        // "contacts" is the pre-ContactsContract authority, still registered as an alias.
        ContactSource("contacts", "Contacts", "People, phone numbers, email addresses and groups", setOf("com.android.contacts", "contacts")),
        ContactSource("call_log", "Call log", "Incoming, outgoing and missed calls", setOf("call_log")),
        ContactSource("calendar", "Calendar", "Calendars, events, attendees and reminders", setOf("com.android.calendar")),
    )

    val DEFAULT_RESTRICTED: Set<String> = ALL.map { it.id }.toSet()

    fun byId(id: String): ContactSource? = ALL.firstOrNull { it.id == id }

    fun authoritiesOf(ids: Set<String>): Set<String> = ids.mapNotNull { byId(it) }.flatMap { it.authorities }.toSet()
}

@Serializable
data class TelephonyPolicy(
    /** RESTRICT = look like no SIM is present; SIMULATE = report [carrierProfile]'s carrier and country. */
    val mode: Mode = Mode.DEFAULT,
    val carrierProfile: String = CarrierProfiles.DEFAULT_ID,
) {
    companion object {
        val SUPPORTED_MODES = listOf(Mode.DEFAULT, Mode.ALLOW, Mode.RESTRICT, Mode.SIMULATE)
    }
}

/** A user-facing set of android.hardware.Sensor TYPE_* values that are restricted together. */
data class SensorGroup(val id: String, val label: String, val description: String, val types: Set<Int>)

object SensorGroups {
    val ALL: List<SensorGroup> = listOf(
        SensorGroup(
            "motion", "Motion", "Accelerometer, gyroscope, gravity, linear acceleration, rotation vectors, significant motion",
            setOf(1, 4, 9, 10, 11, 15, 16, 17, 35),
        ),
        // 3 = deprecated TYPE_ORIENTATION: a fused compass heading still exposed by many devices.
        SensorGroup("compass", "Compass", "Magnetometer, orientation (heading) and geomagnetic rotation vector", setOf(2, 3, 14, 20)),
        SensorGroup("environment", "Environment", "Light, pressure, proximity, humidity, temperature", setOf(5, 6, 8, 12, 13)),
        SensorGroup("activity", "Activity", "Step counter and step detector", setOf(18, 19)),
        SensorGroup("body", "Body", "Heart rate and heart beat", setOf(21, 31)),
    )

    val DEFAULT_RESTRICTED: Set<String> = setOf("motion", "compass")

    fun byId(id: String): SensorGroup? = ALL.firstOrNull { it.id == id }

    fun typesOf(groupIds: Set<String>): Set<Int> = groupIds.mapNotNull { byId(it) }.flatMap { it.types }.toSet()

    fun label(type: Int): String = when (type) {
        1 -> "accelerometer"; 2 -> "magnetic_field"; 3 -> "orientation"; 4 -> "gyroscope"; 5 -> "light"; 6 -> "pressure"
        17 -> "significant_motion"
        8 -> "proximity"; 9 -> "gravity"; 10 -> "linear_acceleration"; 11 -> "rotation_vector"
        12 -> "relative_humidity"; 13 -> "ambient_temperature"; 14 -> "magnetic_field_uncalibrated"
        15 -> "game_rotation_vector"; 16 -> "gyroscope_uncalibrated"; 18 -> "step_detector"
        19 -> "step_counter"; 20 -> "geomagnetic_rotation_vector"; 21 -> "heart_rate"; 31 -> "heart_beat"
        35 -> "accelerometer_uncalibrated"
        else -> "type_$type"
    }
}

/** Everything the user configured for one package. Stored as one JSON record per package. */
@Serializable
data class AppPolicy(
    val packageName: String,
    val enabled: Boolean = false,
    val location: LocationPolicy = LocationPolicy(),
    val clipboard: ClipboardPolicy = ClipboardPolicy(),
    val identifiers: IdentifierPolicy = IdentifierPolicy(),
    val sensors: SensorPolicy = SensorPolicy(),
    val network: NetworkPolicy = NetworkPolicy(),
    val contacts: ContactsPolicy = ContactsPolicy(),
    val telephony: TelephonyPolicy = TelephonyPolicy(),
    val auditEnabled: Boolean = true,
    val updatedAt: Long = 0L,
    /** Last time a setting that is only read at app start changed (see RestartAdvisor). */
    val startupChangedAt: Long = 0L,
)

@Serializable
enum class AuditLevel {
    /** No events except hook-initialization status. */
    OFF,
    /** Events where a policy modified (or tried to modify) behavior. */
    ACTIONS,
    /** Also events where a call was only observed and passed through. */
    VERBOSE,
}

@Serializable
data class CategoryDefaults(
    val location: Mode = Mode.ALLOW,
    val clipboard: Mode = Mode.ALLOW,
    val identifiers: Mode = Mode.ALLOW,
    val sensors: Mode = Mode.ALLOW,
    val network: Mode = Mode.ALLOW,
    val contacts: Mode = Mode.ALLOW,
    val telephony: Mode = Mode.ALLOW,
)

@Serializable
data class GlobalSettings(
    /** Kill switch: when false no hooked process applies any policy. */
    val masterEnabled: Boolean = true,
    val defaults: CategoryDefaults = CategoryDefaults(),
    val auditLevel: AuditLevel = AuditLevel.ACTIONS,
    val redactSensitive: Boolean = true,
    val retentionDays: Int = 14,
    val maxEvents: Int = 5_000,
    /** Post a (rate-limited) notification when a policy blocks an access. */
    val notifyRestricted: Boolean = false,
) {
    companion object {
        /** Category defaults may only be ALLOW or RESTRICT: SIMULATE needs per-app values. */
        val DEFAULT_CHOICES = listOf(Mode.ALLOW, Mode.RESTRICT)
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 365
        const val MIN_MAX_EVENTS = 100
        const val MAX_MAX_EVENTS = 100_000
    }
}
