package io.github.imkamaran.appguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class TemplatesTest {
    private val seed = "00112233445566778899aabbccddeeff"
    private val existing = AppPolicy(
        packageName = "com.example.app",
        enabled = false,
        location = LocationPolicy(Mode.SIMULATE, 1.0, 2.0, 5f, 3.0),
        identifiers = IdentifierPolicy(androidId = Mode.SIMULATE, deviceInfo = Mode.SIMULATE, deviceProfile = "pixel_8", seed = seed),
    )

    @Test
    fun strictTemplateEnablesAndRestricts() {
        val strict = Templates.byId(emptyList(), "builtin.strict")!!
        val p = Templates.apply(strict, existing)
        assertTrue(p.enabled)
        assertEquals(Mode.RESTRICT, p.location.mode)
        assertEquals(Mode.RESTRICT, p.clipboard.mode)
        assertEquals(Mode.RESTRICT, p.sensors.mode)
        assertEquals(setOf("motion", "compass"), p.sensors.restrictedGroups)
    }

    @Test
    fun applyingKeepsPerAppValues() {
        val p = Templates.apply(Templates.BUILT_IN.first(), existing)
        assertEquals(existing.identifiers, p.identifiers)
        assertEquals(1.0, p.location.latitude, 0.0)
        assertEquals(existing.packageName, p.packageName)
    }

    @Test
    fun templatesNeverCarrySimulation() {
        val t = Templates.fromPolicy("u1", "From app", existing)
        assertEquals(Mode.DEFAULT, t.location) // SIMULATE is not template-able
        val forged = Templates.sanitize(PolicyTemplate("x", "  ", clipboard = Mode.SIMULATE, sensorGroups = setOf("motion", "bogus")))
        assertEquals(Mode.DEFAULT, forged.clipboard)
        assertEquals(setOf("motion"), forged.sensorGroups)
        assertEquals("Template", forged.name)
    }

    @Test
    fun userTemplatesCannotClaimBuiltIn() {
        assertFalse(Templates.sanitize(PolicyTemplate("mine", "Mine", builtIn = true)).builtIn)
        assertTrue(Templates.sanitize(Templates.BUILT_IN.first()).builtIn)
    }
}

class BackupCodecTest {
    private val config = ConfigState(
        global = GlobalSettings(auditLevel = AuditLevel.VERBOSE),
        policies = mapOf("com.example.a" to AppPolicy("com.example.a", enabled = true, clipboard = ClipboardPolicy(Mode.RESTRICT))),
        ui = UiSettings(ThemeMode.DARK),
        templates = listOf(PolicyTemplate("u1", "Mine", clipboard = Mode.RESTRICT)),
    )

    @Test
    fun roundTrip() {
        val r = BackupCodec.import(BackupCodec.export(config, 1L, "0.4.0")) as ImportResult.Ok
        assertEquals(config, r.config)
        assertTrue(r.notes.isEmpty())
    }

    @Test
    fun rejectsGarbageForeignAndFutureFiles() {
        assertTrue(BackupCodec.import("") is ImportResult.Rejected)
        assertTrue(BackupCodec.import("{oops") is ImportResult.Rejected)
        assertTrue(BackupCodec.import("""{"format":"something-else","config":{}}""") is ImportResult.Rejected)
        assertTrue(BackupCodec.import("""{"format":"appguard-backup","version":99,"config":{}}""") is ImportResult.Rejected)
        assertTrue(BackupCodec.import("""{"format":"appguard-backup","version":1,"config":{"schema":99}}""") is ImportResult.Rejected)
        assertTrue(BackupCodec.import("""{"model":"Pixel 7","brand":"google"}""") is ImportResult.Rejected)
        assertTrue(BackupCodec.import("""{}""") is ImportResult.Rejected)
        assertTrue(BackupCodec.import("""{"format":"appguard-backup","version":1}""") is ImportResult.Rejected)
    }

    @Test
    fun dropsUnsafeEntriesWithNotes() {
        val bad = config.copy(
            policies = config.policies +
                ("android" to AppPolicy("android", enabled = true)) +
                ("com.example.key" to AppPolicy("com.example.other", enabled = true)),
            templates = config.templates + config.templates.first(),
        )
        val r = BackupCodec.import(BackupCodec.export(bad, 1L, "x")) as ImportResult.Ok
        assertEquals(setOf("com.example.a"), r.config.policies.keys)
        assertEquals(1, r.config.templates.size)
        assertEquals(3, r.notes.size)
    }
}

class RestartAdvisorTest {
    private val base = AppPolicy("com.example.a", enabled = true, startupChangedAt = 1_000L)

    @Test
    fun enablingAndDeviceProfileNeedRestartButLiveSettingsDoNot() {
        assertTrue(RestartAdvisor.startupFieldsDiffer(null, base))
        assertTrue(RestartAdvisor.startupFieldsDiffer(base.copy(enabled = false), base))
        assertTrue(RestartAdvisor.startupFieldsDiffer(base, base.copy(identifiers = IdentifierPolicy(deviceInfo = Mode.SIMULATE))))
        assertFalse(RestartAdvisor.startupFieldsDiffer(base, base.copy(clipboard = ClipboardPolicy(Mode.RESTRICT))))
        assertFalse(RestartAdvisor.startupFieldsDiffer(base, base.copy(enabled = false)))
    }

    @Test
    fun needsRestartComparesWithLastReport() {
        assertTrue(RestartAdvisor.needsRestart(base, null))
        assertTrue(RestartAdvisor.needsRestart(base, 999L))
        assertFalse(RestartAdvisor.needsRestart(base, 1_000L))
        assertFalse(RestartAdvisor.needsRestart(base.copy(enabled = false), null))
    }
}

class ThrottleAndReportTest {
    @Test
    fun throttleAllowsOncePerWindowPerAppAndCategory() {
        val t = NotificationThrottle(windowMs = 100)
        assertTrue(t.allow("a", Category.CLIPBOARD, 0))
        assertFalse(t.allow("a", Category.CLIPBOARD, 50))
        assertTrue(t.allow("a", Category.SENSORS, 50))
        assertTrue(t.allow("b", Category.CLIPBOARD, 50))
        assertTrue(t.allow("a", Category.CLIPBOARD, 100))
    }

    @Test
    fun dayIndexCountsCalendarDays() {
        val tz = TimeZone.getTimeZone("UTC")
        val now = 1_700_000_000_000L // 2023-11-14T22:13:20Z
        assertEquals(6, ReportMath.dayIndex(now, now, 7, tz))
        assertEquals(6, ReportMath.dayIndex(ReportMath.startOfDay(now, tz), now, 7, tz))
        assertEquals(5, ReportMath.dayIndex(now - 86_400_000L, now, 7, tz))
        assertEquals(0, ReportMath.dayIndex(now - 6 * 86_400_000L, now, 7, tz))
        assertEquals(-1, ReportMath.dayIndex(now - 7 * 86_400_000L, now, 7, tz))
    }

    @Test
    fun dayIndexIsDstSafe() {
        val tz = TimeZone.getTimeZone("Europe/London")
        // 2024-03-31 is a 23-hour day in London; noon on Apr 2 must be index 6 with Mar 27 as day 0.
        val now = 1_712_059_200_000L // 2024-04-02T12:00:00Z (13:00 BST)
        assertEquals(6, ReportMath.dayIndex(now, now, 7, tz))
        assertEquals(4, ReportMath.dayIndex(1_711_886_400_000L, now, 7, tz)) // 2024-03-31T12:00Z
        assertEquals(0, ReportMath.dayIndex(1_711_540_800_000L, now, 7, tz)) // 2024-03-27T12:00Z
    }
}
