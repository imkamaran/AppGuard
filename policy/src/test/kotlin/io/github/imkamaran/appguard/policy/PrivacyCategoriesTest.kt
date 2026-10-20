package io.github.imkamaran.appguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyCategoriesTest {
    private val pkg = "com.example.target"
    private val seed = "00112233445566778899aabbccddeeff"
    private val global = GlobalSettings()

    private fun resolve(policy: AppPolicy, g: GlobalSettings = global) = PolicyResolver.resolve(pkg, policy, g)!!

    private fun enabled(block: AppPolicy.() -> AppPolicy) =
        AppPolicy(pkg, enabled = true, identifiers = IdentifierPolicy(seed = seed)).block()

    @Test
    fun newCategoriesDefaultToAllow() {
        val e = resolve(enabled { this }).effective
        assertEquals(Mode.ALLOW, e.network)
        assertEquals(Mode.ALLOW, e.contacts)
        assertEquals(Mode.ALLOW, e.telephony)
        assertTrue(e.restrictedContactAuthorities.isEmpty())
    }

    @Test
    fun newCategoriesInheritRestrictDefaults() {
        val g = global.copy(defaults = CategoryDefaults(network = Mode.RESTRICT, contacts = Mode.RESTRICT, telephony = Mode.RESTRICT))
        val e = resolve(enabled { this }, g).effective
        assertEquals(Mode.RESTRICT, e.network)
        assertEquals(Mode.RESTRICT, e.contacts)
        assertEquals(Mode.RESTRICT, e.telephony)
        assertTrue("com.android.contacts" in e.restrictedContactAuthorities)
        assertTrue("call_log" in e.restrictedContactAuthorities)
        assertTrue("com.android.calendar" in e.restrictedContactAuthorities)
    }

    @Test
    fun networkSimulationUsesStableSyntheticValues() {
        val e = resolve(enabled { copy(network = NetworkPolicy(Mode.SIMULATE)) }).effective
        assertEquals(Mode.SIMULATE, e.network)
        assertEquals(SyntheticIds.wifiSsid(pkg, seed), e.syntheticSsid)
        assertEquals(SyntheticIds.wifiBssid(pkg, seed), e.syntheticBssid)
    }

    @Test
    fun networkSimulationNeedsASeed() {
        val p = AppPolicy(pkg, enabled = true, network = NetworkPolicy(Mode.SIMULATE))
        val r = resolve(p)
        assertEquals(Mode.ALLOW, r.effective.network)
        assertNull(r.effective.syntheticSsid)
        assertTrue(r.issues.any { it.startsWith("Network simulation disabled") })
    }

    @Test
    fun contactsRestrictionCoversOnlySelectedSources() {
        val e = resolve(enabled { copy(contacts = ContactsPolicy(Mode.RESTRICT, setOf("calendar"))) }).effective
        assertEquals(setOf("com.android.calendar"), e.restrictedContactAuthorities)
    }

    @Test
    fun contactsRestrictionWithoutSourcesFailsOpen() {
        val r = resolve(enabled { copy(contacts = ContactsPolicy(Mode.RESTRICT, emptySet())) })
        assertEquals(Mode.ALLOW, r.effective.contacts)
        assertTrue(r.issues.isNotEmpty())
    }

    @Test
    fun contactsCannotBeSimulated() {
        val r = resolve(enabled { copy(contacts = ContactsPolicy(Mode.SIMULATE)) })
        assertEquals(Mode.ALLOW, r.effective.contacts)
    }

    @Test
    fun telephonySimulationResolvesCarrier() {
        val e = resolve(enabled { copy(telephony = TelephonyPolicy(Mode.SIMULATE, "vodafone_uk")) }).effective
        assertEquals(Mode.SIMULATE, e.telephony)
        assertEquals("23415", e.carrier?.mccMnc)
    }

    @Test
    fun unknownCarrierFailsOpen() {
        val r = resolve(enabled { copy(telephony = TelephonyPolicy(Mode.SIMULATE, "nope")) })
        assertEquals(Mode.ALLOW, r.effective.telephony)
        assertNull(r.effective.carrier)
    }

    @Test
    fun syntheticWifiValuesAreWellFormedAndPerApp() {
        val ssid = SyntheticIds.wifiSsid(pkg, seed)
        val bssid = SyntheticIds.wifiBssid(pkg, seed)
        assertEquals(ssid, SyntheticIds.wifiSsid(pkg, seed))
        assertNotEquals(bssid, SyntheticIds.wifiBssid("com.example.other", seed))
        assertTrue(bssid, Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$").matches(bssid))
        val first = bssid.substring(0, 2).toInt(16)
        assertEquals("unicast, globally administered", 0, first and 0x03)
        assertFalse(ssid.contains('"'))
    }

    @Test
    fun carrierProfilesAreWellFormed() {
        assertTrue(CarrierProfiles.ALL.all { it.isWellFormed() })
        assertTrue(CarrierProfiles.byId(CarrierProfiles.DEFAULT_ID) != null)
    }

    @Test
    fun oldRecordsWithoutNewFieldsStillDecode() {
        val raw = """{"packageName":"$pkg","enabled":true,"clipboard":{"mode":"RESTRICT"}}"""
        val p = PolicySchema.decodePolicy(pkg, raw)!!
        assertEquals(Mode.DEFAULT, p.network.mode)
        assertEquals(ContactSources.DEFAULT_RESTRICTED, p.contacts.restrictedSources)
        assertEquals(CarrierProfiles.DEFAULT_ID, p.telephony.carrierProfile)
    }

    @Test
    fun templatesCarryNewCategoriesButNotCarrier() {
        val existing = enabled { copy(telephony = TelephonyPolicy(Mode.SIMULATE, "orange_fr")) }
        val strict = Templates.apply(Templates.byId(emptyList(), "builtin.strict")!!, existing)
        assertEquals(Mode.RESTRICT, strict.network.mode)
        assertEquals(Mode.RESTRICT, strict.contacts.mode)
        assertEquals(Mode.RESTRICT, strict.telephony.mode)
        assertEquals("orange_fr", strict.telephony.carrierProfile)

        val t = Templates.fromPolicy("u", "x", existing)
        assertEquals(Mode.DEFAULT, t.telephony) // SIMULATE is not template-able
    }

    @Test
    fun summaryListsOnlyCategoriesThatChangeBehavior() {
        val p = enabled {
            copy(
                clipboard = ClipboardPolicy(Mode.RESTRICT),
                sensors = SensorPolicy(Mode.ALLOW),
                telephony = TelephonyPolicy(Mode.SIMULATE),
            )
        }
        assertEquals(listOf(Category.TELEPHONY, Category.CLIPBOARD), PolicySummary.activeCategories(pkg, p, global))
        assertTrue(PolicySummary.activeCategories(pkg, p.copy(enabled = false), global).isEmpty())
        assertTrue(PolicySummary.activeCategories(pkg, p, global.copy(masterEnabled = false)).isEmpty())
    }

    @Test
    fun templateSanitizeDropsUnknownContactSources() {
        val t = Templates.sanitize(PolicyTemplate("u", "x", contactSources = setOf("contacts", "sms")))
        assertEquals(setOf("contacts"), t.contactSources)
    }
}
