package io.github.imkamaran.appguard.policy

/**
 * The concrete behavior a hooked process applies. Never contains [Mode.DEFAULT]; a mode that could
 * not be honored (unsupported or invalid parameters) has already been downgraded to [Mode.ALLOW].
 */
data class EffectivePolicy(
    val packageName: String,
    val location: Mode,
    val locationSpec: LocationSpec?,
    val clipboard: Mode,
    val androidId: Mode,
    val syntheticAndroidId: String?,
    val deviceInfo: Mode,
    val deviceProfile: DeviceProfile?,
    val sensors: Mode,
    /** Sensor TYPE_* values hidden when [sensors] is RESTRICT; empty otherwise. */
    val restrictedSensorTypes: Set<Int>,
    val network: Mode = Mode.ALLOW,
    /** Synthetic Wi-Fi SSID (unquoted) and BSSID when [network] is SIMULATE. */
    val syntheticSsid: String? = null,
    val syntheticBssid: String? = null,
    val contacts: Mode = Mode.ALLOW,
    /** Content provider authorities answered with empty results when [contacts] is RESTRICT. */
    val restrictedContactAuthorities: Set<String> = emptySet(),
    val telephony: Mode = Mode.ALLOW,
    val carrier: CarrierProfile? = null,
    val auditLevel: AuditLevel,
    val redact: Boolean,
    /** AppPolicy.updatedAt; identifies the saved policy version (used by the crash guard). */
    val revision: Long,
)

data class Resolution(
    val effective: EffectivePolicy,
    /** Human-readable reasons a configured mode was downgraded to ALLOW (fail open). */
    val issues: List<String>,
)

object PolicyResolver {

    /**
     * Resolves what [requestedPackage] should get. Returns null when nothing applies: the kill
     * switch is off, the policy is disabled, or the record belongs to a different package (never
     * apply one app's policy to another app's process).
     */
    fun resolve(requestedPackage: String, policy: AppPolicy?, global: GlobalSettings): Resolution? {
        if (policy == null || !global.masterEnabled || !policy.enabled) return null
        if (policy.packageName != requestedPackage) return null
        if (ProtectedPackages.isProtected(requestedPackage)) return null

        val issues = mutableListOf<String>()
        val d = global.defaults

        var location = pick(policy.location.mode, d.location, LocationPolicy.SUPPORTED_MODES, "Location", issues)
        var locationSpec: LocationSpec? = null
        if (location == Mode.SIMULATE) {
            when (val v = LocationSpec.from(policy.location)) {
                is Validated.Valid -> locationSpec = v.value
                is Validated.Invalid -> {
                    issues += "Location simulation disabled: ${v.errors.joinToString(" ")}"
                    location = Mode.ALLOW
                }
            }
        }

        val clipboard = pick(policy.clipboard.mode, d.clipboard, ClipboardPolicy.SUPPORTED_MODES, "Clipboard", issues)

        val ids = policy.identifiers
        var androidId = pick(ids.androidId, d.identifiers, IdentifierPolicy.SUPPORTED_MODES, "Android ID", issues)
        var syntheticAndroidId: String? = null
        if (androidId == Mode.SIMULATE) {
            if (SyntheticIds.isValidSeed(ids.seed)) {
                syntheticAndroidId = SyntheticIds.androidId(policy.packageName, ids.seed)
            } else {
                issues += "Android ID simulation disabled: identity seed is missing or malformed."
                androidId = Mode.ALLOW
            }
        }

        var deviceInfo = pick(ids.deviceInfo, d.identifiers, IdentifierPolicy.SUPPORTED_MODES, "Device info", issues)
        var profile: DeviceProfile? = null
        if (deviceInfo == Mode.SIMULATE) {
            profile = DeviceProfiles.byId(ids.deviceProfile)?.takeIf { it.isWellFormed() }
            if (profile == null) {
                issues += "Device info simulation disabled: unknown profile '${ids.deviceProfile}'."
                deviceInfo = Mode.ALLOW
            }
        }

        var sensors = pick(policy.sensors.mode, d.sensors, SensorPolicy.SUPPORTED_MODES, "Sensors", issues)
        var restrictedSensorTypes = emptySet<Int>()
        if (sensors == Mode.RESTRICT) {
            restrictedSensorTypes = SensorGroups.typesOf(policy.sensors.restrictedGroups)
            if (restrictedSensorTypes.isEmpty()) {
                issues += "Sensor restriction disabled: no sensor groups selected."
                sensors = Mode.ALLOW
            }
        }

        var network = pick(policy.network.mode, d.network, NetworkPolicy.SUPPORTED_MODES, "Network", issues)
        var syntheticSsid: String? = null
        var syntheticBssid: String? = null
        if (network == Mode.SIMULATE) {
            if (SyntheticIds.isValidSeed(ids.seed)) {
                syntheticSsid = SyntheticIds.wifiSsid(policy.packageName, ids.seed)
                syntheticBssid = SyntheticIds.wifiBssid(policy.packageName, ids.seed)
            } else {
                issues += "Network simulation disabled: identity seed is missing or malformed."
                network = Mode.ALLOW
            }
        }

        var contacts = pick(policy.contacts.mode, d.contacts, ContactsPolicy.SUPPORTED_MODES, "Contacts & calendar", issues)
        var contactAuthorities = emptySet<String>()
        if (contacts == Mode.RESTRICT) {
            contactAuthorities = ContactSources.authoritiesOf(policy.contacts.restrictedSources)
            if (contactAuthorities.isEmpty()) {
                issues += "Contacts & calendar restriction disabled: no data sources selected."
                contacts = Mode.ALLOW
            }
        }

        var telephony = pick(policy.telephony.mode, d.telephony, TelephonyPolicy.SUPPORTED_MODES, "Phone & SIM", issues)
        var carrier: CarrierProfile? = null
        if (telephony == Mode.SIMULATE) {
            carrier = CarrierProfiles.byId(policy.telephony.carrierProfile)?.takeIf { it.isWellFormed() }
            if (carrier == null) {
                issues += "Phone & SIM simulation disabled: unknown carrier '${policy.telephony.carrierProfile}'."
                telephony = Mode.ALLOW
            }
        }

        val auditLevel = if (policy.auditEnabled) global.auditLevel else AuditLevel.OFF

        return Resolution(
            EffectivePolicy(
                packageName = policy.packageName,
                location = location,
                locationSpec = locationSpec,
                clipboard = clipboard,
                androidId = androidId,
                syntheticAndroidId = syntheticAndroidId,
                deviceInfo = deviceInfo,
                deviceProfile = profile,
                sensors = sensors,
                restrictedSensorTypes = restrictedSensorTypes,
                network = network,
                syntheticSsid = syntheticSsid,
                syntheticBssid = syntheticBssid,
                contacts = contacts,
                restrictedContactAuthorities = contactAuthorities,
                telephony = telephony,
                carrier = carrier,
                auditLevel = auditLevel,
                redact = global.redactSensitive,
                revision = policy.updatedAt,
            ),
            issues,
        )
    }

    private fun pick(
        configured: Mode,
        categoryDefault: Mode,
        supported: List<Mode>,
        label: String,
        issues: MutableList<String>,
    ): Mode {
        val mode = if (configured == Mode.DEFAULT) {
            // Defaults can only be ALLOW/RESTRICT; anything else in storage is treated as ALLOW.
            if (categoryDefault in GlobalSettings.DEFAULT_CHOICES) categoryDefault else Mode.ALLOW
        } else {
            configured
        }
        if (mode !in supported) {
            issues += "$label: mode $mode is not supported; behavior left unmodified."
            return Mode.ALLOW
        }
        return mode
    }
}

object PolicySummary {
    /** User-facing privacy categories in display order. */
    val PRIVACY_CATEGORIES: List<Category> = listOf(
        Category.LOCATION, Category.NETWORK, Category.TELEPHONY, Category.IDENTIFIERS,
        Category.CONTACTS, Category.CLIPBOARD, Category.SENSORS,
    )

    /** Categories this policy actually changes; Allow and audit-only sensors are not protection. */
    fun activeCategories(e: EffectivePolicy): List<Category> = PRIVACY_CATEGORIES.filter { c ->
        when (c) {
            Category.LOCATION -> e.location != Mode.ALLOW
            Category.NETWORK -> e.network != Mode.ALLOW
            Category.TELEPHONY -> e.telephony != Mode.ALLOW
            Category.IDENTIFIERS -> e.androidId != Mode.ALLOW || e.deviceInfo != Mode.ALLOW
            Category.CONTACTS -> e.contacts != Mode.ALLOW
            Category.CLIPBOARD -> e.clipboard != Mode.ALLOW
            Category.SENSORS -> e.sensors == Mode.RESTRICT
            Category.SYSTEM -> false
        }
    }

    fun activeCategories(packageName: String, policy: AppPolicy?, global: GlobalSettings): List<Category> =
        PolicyResolver.resolve(packageName, policy, global)?.let { activeCategories(it.effective) } ?: emptyList()
}

/** Packages AppGuard refuses to hook, whatever the configuration says. */
object ProtectedPackages {
    private val NEVER = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.providers.settings",
        "com.android.shell",
        "com.android.settings",
        "com.android.location.fused",
        "com.google.android.gms",
        "com.google.android.gsf",
    )

    fun isProtected(packageName: String): Boolean =
        packageName in NEVER || packageName == PolicySchema.MODULE_PACKAGE

    fun reason(packageName: String): String? = when {
        packageName == "com.google.android.gms" || packageName == "com.google.android.gsf" ->
            "Google Play services provides location and identity to other apps; hooking it would affect every app."
        packageName in NEVER -> "System component involved in emergency calls, location or settings."
        isProtected(packageName) -> "AppGuard does not hook itself."
        else -> null
    }
}
