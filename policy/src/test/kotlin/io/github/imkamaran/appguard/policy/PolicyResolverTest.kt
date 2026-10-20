package io.github.imkamaran.appguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyResolverTest {
    private val pkg = "com.example.target"
    private val seed = "00112233445566778899aabbccddeeff"
    private val global = GlobalSettings()

    private fun enabled(block: AppPolicy.() -> AppPolicy = { this }) = AppPolicy(pkg, enabled = true).block()

    @Test
    fun nothingAppliesWithoutAnEnabledPolicy() {
        assertNull(PolicyResolver.resolve(pkg, null, global))
        assertNull(PolicyResolver.resolve(pkg, AppPolicy(pkg, enabled = false), global))
        assertNull(PolicyResolver.resolve(pkg, enabled(), global.copy(masterEnabled = false)))
    }

    @Test
    fun policyIsNeverAppliedToAnotherPackage() {
        assertNull(PolicyResolver.resolve("com.example.other", enabled(), global))
    }

    @Test
    fun protectedPackagesAreNeverResolved() {
        for (p in listOf("android", "com.android.phone", "com.google.android.gms", PolicySchema.MODULE_PACKAGE)) {
            assertNull(p, PolicyResolver.resolve(p, AppPolicy(p, enabled = true), global))
        }
    }

    @Test
    fun defaultModesInheritCategoryDefaults() {
        val r = PolicyResolver.resolve(pkg, enabled(), global)!!.effective
        assertEquals(Mode.ALLOW, r.location)
        assertEquals(Mode.ALLOW, r.androidId)

        val strict = global.copy(defaults = CategoryDefaults(location = Mode.RESTRICT, clipboard = Mode.RESTRICT))
        val s = PolicyResolver.resolve(pkg, enabled(), strict)!!.effective
        assertEquals(Mode.RESTRICT, s.location)
        assertEquals(Mode.RESTRICT, s.clipboard)
    }

    @Test
    fun simulateIsNotAValidCategoryDefault() {
        val bad = global.copy(defaults = CategoryDefaults(location = Mode.SIMULATE))
        assertEquals(Mode.ALLOW, PolicyResolver.resolve(pkg, enabled(), bad)!!.effective.location)
    }

    @Test
    fun explicitModeOverridesDefault() {
        val strict = global.copy(defaults = CategoryDefaults(location = Mode.RESTRICT))
        val r = PolicyResolver.resolve(pkg, enabled { copy(location = LocationPolicy(Mode.ALLOW)) }, strict)!!
        assertEquals(Mode.ALLOW, r.effective.location)
    }

    @Test
    fun validLocationSimulationCarriesSpec() {
        val r = PolicyResolver.resolve(pkg, enabled { copy(location = LocationPolicy(Mode.SIMULATE, 48.8584, 2.2945)) }, global)!!
        assertEquals(Mode.SIMULATE, r.effective.location)
        assertEquals(48.8584, r.effective.locationSpec!!.latitude, 0.0)
        assertEquals(LocationSpec.DEFAULT_ACCURACY_M, r.effective.locationSpec!!.accuracyMeters, 0f)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun invalidLocationSimulationFailsOpen() {
        val r = PolicyResolver.resolve(pkg, enabled { copy(location = LocationPolicy(Mode.SIMULATE, 123.0, 2.0)) }, global)!!
        assertEquals(Mode.ALLOW, r.effective.location)
        assertNull(r.effective.locationSpec)
        assertEquals(1, r.issues.size)
    }

    @Test
    fun androidIdSimulationNeedsAValidSeed() {
        val ok = PolicyResolver.resolve(pkg, enabled { copy(identifiers = IdentifierPolicy(androidId = Mode.SIMULATE, seed = seed)) }, global)!!
        assertEquals(Mode.SIMULATE, ok.effective.androidId)
        assertEquals(SyntheticIds.androidId(pkg, seed), ok.effective.syntheticAndroidId)

        val bad = PolicyResolver.resolve(pkg, enabled { copy(identifiers = IdentifierPolicy(androidId = Mode.SIMULATE, seed = "xyz")) }, global)!!
        assertEquals(Mode.ALLOW, bad.effective.androidId)
        assertNull(bad.effective.syntheticAndroidId)
        assertTrue(bad.issues.isNotEmpty())
    }

    @Test
    fun unknownDeviceProfileFailsOpen() {
        val r = PolicyResolver.resolve(pkg, enabled { copy(identifiers = IdentifierPolicy(deviceInfo = Mode.SIMULATE, deviceProfile = "nokia_3310")) }, global)!!
        assertEquals(Mode.ALLOW, r.effective.deviceInfo)
        val ok = PolicyResolver.resolve(pkg, enabled { copy(identifiers = IdentifierPolicy(deviceInfo = Mode.SIMULATE, deviceProfile = "galaxy_s23")) }, global)!!
        assertEquals("SM-S911B", ok.effective.deviceProfile!!.model)
    }

    @Test
    fun unsupportedModesAreDowngradedToAllow() {
        val r = PolicyResolver.resolve(
            pkg,
            enabled { copy(clipboard = ClipboardPolicy(Mode.SIMULATE), identifiers = IdentifierPolicy(androidId = Mode.RESTRICT)) },
            global,
        )!!
        assertEquals(Mode.ALLOW, r.effective.clipboard)
        assertEquals(Mode.ALLOW, r.effective.androidId)
        assertEquals(2, r.issues.size)
    }

    @Test
    fun sensorRestrictionResolvesGroupsToTypes() {
        val r = PolicyResolver.resolve(pkg, enabled { copy(sensors = SensorPolicy(Mode.RESTRICT, setOf("motion"))) }, global)!!
        assertEquals(Mode.RESTRICT, r.effective.sensors)
        assertTrue(1 in r.effective.restrictedSensorTypes) // accelerometer
        assertTrue(4 in r.effective.restrictedSensorTypes) // gyroscope
        assertTrue(2 !in r.effective.restrictedSensorTypes) // compass not selected
    }

    @Test
    fun sensorRestrictionWithoutGroupsFailsOpen() {
        val r = PolicyResolver.resolve(pkg, enabled { copy(sensors = SensorPolicy(Mode.RESTRICT, emptySet())) }, global)!!
        assertEquals(Mode.ALLOW, r.effective.sensors)
        assertTrue(r.effective.restrictedSensorTypes.isEmpty())
        assertEquals(1, r.issues.size)
    }

    @Test
    fun compassGroupCoversLegacyOrientationSensor() {
        assertTrue(3 in SensorGroups.typesOf(setOf("compass"))) // TYPE_ORIENTATION reveals heading
        assertTrue(17 in SensorGroups.typesOf(setOf("motion"))) // TYPE_SIGNIFICANT_MOTION
    }

    @Test
    fun unknownSensorGroupsAreIgnored() {
        assertEquals(setOf(18, 19), SensorGroups.typesOf(setOf("activity", "nonexistent")))
    }

    @Test
    fun revisionIsCarried() {
        val r = PolicyResolver.resolve(pkg, enabled { copy(updatedAt = 42L) }, global)!!
        assertEquals(42L, r.effective.revision)
    }

    @Test
    fun uiSettingsRoundTripAndDefault() {
        val s = ConfigState(ui = UiSettings(ThemeMode.DARK, dynamicColor = false))
        val raw = PolicySchema.json.encodeToString(ConfigState.serializer(), s)
        assertEquals(s, PolicySchema.json.decodeFromString(ConfigState.serializer(), raw))
        val legacy = PolicySchema.json.decodeFromString(ConfigState.serializer(), """{"schema":1}""")
        assertEquals(ThemeMode.SYSTEM, legacy.ui.theme)
    }

    @Test
    fun perAppAuditSwitchOverridesGlobalLevel() {
        val on = PolicyResolver.resolve(pkg, enabled(), global.copy(auditLevel = AuditLevel.VERBOSE))!!
        assertEquals(AuditLevel.VERBOSE, on.effective.auditLevel)
        val off = PolicyResolver.resolve(pkg, enabled { copy(auditEnabled = false) }, global.copy(auditLevel = AuditLevel.VERBOSE))!!
        assertEquals(AuditLevel.OFF, off.effective.auditLevel)
    }

    @Test
    fun resolutionIsDeterministic() {
        val p = enabled { copy(identifiers = IdentifierPolicy(androidId = Mode.SIMULATE, seed = seed)) }
        assertEquals(PolicyResolver.resolve(pkg, p, global), PolicyResolver.resolve(pkg, p, global))
        assertNotNull(PolicyResolver.resolve(pkg, p, global))
    }
}
