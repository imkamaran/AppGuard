package io.github.imkamaran.appguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicySchemaTest {
    private val pkg = "com.example.target"

    private val full = AppPolicy(
        packageName = pkg,
        enabled = true,
        location = LocationPolicy(Mode.SIMULATE, 51.5007, -0.1246, 25f, 11.0),
        clipboard = ClipboardPolicy(Mode.RESTRICT),
        identifiers = IdentifierPolicy(Mode.SIMULATE, Mode.ALLOW, "pixel_8", "0123456789abcdef0123456789abcdef"),
        sensors = SensorPolicy(Mode.ALLOW),
        auditEnabled = false,
        updatedAt = 1234L,
    )

    @Test
    fun policyRoundTrips() {
        val raw = PolicySchema.encodePolicy(full)
        assertEquals(full, PolicySchema.decodePolicy(pkg, raw))
    }

    @Test
    fun globalRoundTrips() {
        val g = GlobalSettings(masterEnabled = false, auditLevel = AuditLevel.VERBOSE, redactSensitive = false, retentionDays = 3, maxEvents = 200)
        assertEquals(g, PolicySchema.decodeGlobal(PolicySchema.encodeGlobal(g)))
    }

    @Test
    fun missingFieldsTakeDefaultsAndUnknownFieldsAreIgnored() {
        val raw = """{"packageName":"$pkg","enabled":true,"futureField":{"x":1},"location":{"mode":"RESTRICT","newKnob":3}}"""
        val p = PolicySchema.decodePolicy(pkg, raw)!!
        assertTrue(p.enabled)
        assertEquals(Mode.RESTRICT, p.location.mode)
        assertEquals(Mode.DEFAULT, p.clipboard.mode)
        assertTrue(p.auditEnabled)
    }

    @Test
    fun unknownEnumValueFallsBackToDefault() {
        val raw = """{"packageName":"$pkg","enabled":true,"location":{"mode":"TELEPORT"}}"""
        assertEquals(Mode.DEFAULT, PolicySchema.decodePolicy(pkg, raw)!!.location.mode)
    }

    @Test
    fun corruptRecordsDecodeToNull() {
        assertNull(PolicySchema.decodePolicy(pkg, null))
        assertNull(PolicySchema.decodePolicy(pkg, ""))
        assertNull(PolicySchema.decodePolicy(pkg, "{not json"))
        assertNull(PolicySchema.decodePolicy(pkg, "[]"))
        assertNull(PolicySchema.decodePolicy(pkg, """{"enabled":true}""")) // no packageName
        assertNull(PolicySchema.decodePolicy(pkg, """{"packageName":"$pkg","location":{"latitude":"north"}}"""))
    }

    @Test
    fun oversizedRecordIsRejected() {
        val raw = """{"packageName":"$pkg","pad":"${"x".repeat(PolicySchema.MAX_RECORD_CHARS)}"}"""
        assertNull(PolicySchema.decodePolicy(pkg, raw))
    }

    @Test
    fun recordForAnotherPackageIsNeverReturned() {
        val raw = PolicySchema.encodePolicy(full.copy(packageName = "com.example.other"))
        assertNull(PolicySchema.decodePolicy(pkg, raw))
    }

    @Test
    fun corruptGlobalIsNullButMissingGlobalIsDefault() {
        assertEquals(GlobalSettings(), PolicySchema.decodeGlobal(null))
        assertNull(PolicySchema.decodeGlobal("{oops"))
    }

    @Test
    fun globalLimitsAreClamped() {
        val g = PolicySchema.decodeGlobal("""{"retentionDays":0,"maxEvents":99999999}""")!!
        assertEquals(GlobalSettings.MIN_RETENTION_DAYS, g.retentionDays)
        assertEquals(GlobalSettings.MAX_MAX_EVENTS, g.maxEvents)
    }

    @Test
    fun schemaVersionGate() {
        assertTrue(PolicySchema.isSupportedSchema(PolicySchema.SCHEMA_VERSION))
        assertFalse(PolicySchema.isSupportedSchema(PolicySchema.SCHEMA_VERSION + 1))
        assertFalse(PolicySchema.isSupportedSchema(0))
    }

    @Test
    fun packageNameValidation() {
        assertTrue(PolicySchema.isValidPackageName("com.example.app"))
        assertTrue(PolicySchema.isValidPackageName("a.b_c.D1"))
        assertFalse(PolicySchema.isValidPackageName("noDots"))
        assertFalse(PolicySchema.isValidPackageName("com..example"))
        assertFalse(PolicySchema.isValidPackageName("com.example/../x"))
        assertNotNull(PolicySchema.policyKey("com.example.app"))
    }
}
