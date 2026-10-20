package io.github.imkamaran.appguard.policy

import kotlinx.serialization.Serializable

/**
 * A reusable set of protective settings. Templates only carry Default/Allow/Restrict choices and
 * the audit switch; per-app values (simulation settings, identity seed, coordinates) are never
 * copied into or out of a template.
 */
@Serializable
data class PolicyTemplate(
    val id: String,
    val name: String,
    /** DEFAULT, ALLOW or RESTRICT. */
    val location: Mode = Mode.DEFAULT,
    /** DEFAULT, ALLOW or RESTRICT. */
    val clipboard: Mode = Mode.DEFAULT,
    /** DEFAULT, ALLOW (audit only) or RESTRICT. */
    val sensors: Mode = Mode.DEFAULT,
    val sensorGroups: Set<String> = SensorGroups.DEFAULT_RESTRICTED,
    val network: Mode = Mode.DEFAULT,
    val contacts: Mode = Mode.DEFAULT,
    val contactSources: Set<String> = ContactSources.DEFAULT_RESTRICTED,
    val telephony: Mode = Mode.DEFAULT,
    val auditEnabled: Boolean = true,
    val builtIn: Boolean = false,
)

object Templates {
    private val TEMPLATE_MODES = setOf(Mode.DEFAULT, Mode.ALLOW, Mode.RESTRICT)
    const val MAX_NAME = 40
    const val MAX_USER_TEMPLATES = 50

    val BUILT_IN: List<PolicyTemplate> = listOf(
        PolicyTemplate(
            id = "builtin.strict", name = "Strict",
            location = Mode.RESTRICT, clipboard = Mode.RESTRICT, sensors = Mode.RESTRICT,
            sensorGroups = setOf("motion", "compass"),
            network = Mode.RESTRICT, contacts = Mode.RESTRICT, telephony = Mode.RESTRICT, builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin.personal", name = "Personal data guard",
            clipboard = Mode.RESTRICT, contacts = Mode.RESTRICT, builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin.antitrack", name = "Anti-tracking",
            location = Mode.RESTRICT, network = Mode.RESTRICT, telephony = Mode.RESTRICT, builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin.clipboard", name = "Clipboard guard",
            clipboard = Mode.RESTRICT, builtIn = true,
        ),
        PolicyTemplate(
            id = "builtin.audit", name = "Audit only",
            location = Mode.ALLOW, clipboard = Mode.ALLOW, sensors = Mode.ALLOW,
            network = Mode.ALLOW, contacts = Mode.ALLOW, telephony = Mode.ALLOW, builtIn = true,
        ),
    )

    fun all(user: List<PolicyTemplate>): List<PolicyTemplate> = BUILT_IN + user

    fun byId(user: List<PolicyTemplate>, id: String): PolicyTemplate? = all(user).firstOrNull { it.id == id }

    /** Captures [policy]'s protective choices. Modes a template cannot hold become DEFAULT. */
    fun fromPolicy(id: String, name: String, policy: AppPolicy): PolicyTemplate = sanitize(
        PolicyTemplate(
            id = id,
            name = name,
            location = policy.location.mode,
            clipboard = policy.clipboard.mode,
            sensors = policy.sensors.mode,
            sensorGroups = policy.sensors.restrictedGroups,
            network = policy.network.mode,
            contacts = policy.contacts.mode,
            contactSources = policy.contacts.restrictedSources,
            telephony = policy.telephony.mode,
            auditEnabled = policy.auditEnabled,
        ),
    )

    /**
     * Applies [template] to [existing] and enables the policy. Everything a template does not
     * describe (identifier settings, seed, coordinates, carrier profile) is kept from [existing].
     */
    fun apply(template: PolicyTemplate, existing: AppPolicy): AppPolicy {
        val t = sanitize(template)
        return existing.copy(
            enabled = true,
            location = existing.location.copy(mode = t.location),
            clipboard = existing.clipboard.copy(mode = t.clipboard),
            sensors = SensorPolicy(mode = t.sensors, restrictedGroups = t.sensorGroups),
            network = existing.network.copy(mode = t.network),
            contacts = ContactsPolicy(mode = t.contacts, restrictedSources = t.contactSources),
            telephony = existing.telephony.copy(mode = t.telephony),
            auditEnabled = t.auditEnabled,
        )
    }

    fun sanitize(t: PolicyTemplate): PolicyTemplate = t.copy(
        name = t.name.trim().take(MAX_NAME).ifEmpty { "Template" },
        location = t.location.allowed(),
        clipboard = t.clipboard.allowed(),
        sensors = t.sensors.allowed(),
        sensorGroups = t.sensorGroups.filter { SensorGroups.byId(it) != null }.toSet(),
        network = t.network.allowed(),
        contacts = t.contacts.allowed(),
        contactSources = t.contactSources.filter { ContactSources.byId(it) != null }.toSet(),
        telephony = t.telephony.allowed(),
        builtIn = false,
    ).let { if (t.builtIn && BUILT_IN.any { b -> b.id == t.id }) it.copy(builtIn = true) else it }

    private fun Mode.allowed(): Mode = takeIf { it in TEMPLATE_MODES } ?: Mode.DEFAULT
}
