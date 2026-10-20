package io.github.imkamaran.appguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditTest {
    private fun event(detail: String = "", api: String = "LocationManager.getLastKnownLocation") = AuditEvent(
        timestamp = 1_700_000_000_000L,
        packageName = "com.example.target",
        pid = 4242,
        category = Category.LOCATION,
        api = api,
        policy = Mode.SIMULATE,
        action = AuditAction.SIMULATED,
        outcome = Outcome.SUCCEEDED,
        detail = detail,
    )

    @Test
    fun batchRoundTrips() {
        val batch = listOf(event("a"), event("b").copy(count = 7))
        assertEquals(batch, AuditCodec.decodeBatch(AuditCodec.encodeBatch(batch)))
    }

    @Test
    fun malformedBatchesDecodeToEmpty() {
        assertTrue(AuditCodec.decodeBatch(null).isEmpty())
        assertTrue(AuditCodec.decodeBatch("{").isEmpty())
        assertTrue(AuditCodec.decodeBatch("""[{"timestamp":"x"}]""").isEmpty())
        assertTrue(AuditCodec.decodeBatch("x".repeat(AuditCodec.MAX_PAYLOAD_CHARS + 1)).isEmpty())
    }

    @Test
    fun batchesAndFieldsAreClamped() {
        val many = List(AuditCodec.MAX_BATCH + 20) { event() }
        assertEquals(AuditCodec.MAX_BATCH, AuditCodec.decodeBatch(AuditCodec.encodeBatch(many)).size)

        val raw = AuditCodec.encodeBatch(listOf(event("d".repeat(1000), "a".repeat(1000)).copy(count = -5)))
        val e = AuditCodec.decodeBatch(raw).single()
        assertEquals(AuditCodec.MAX_DETAIL_CHARS, e.detail.length)
        assertEquals(AuditCodec.MAX_API_CHARS, e.api.length)
        assertEquals(1, e.count)
    }

    @Test
    fun redactionHidesValuesByDefault() {
        assertEquals(Redact.PLACEHOLDER, Redact.coordinates(51.50073, -0.12463, redact = true))
        assertEquals("51.50,-0.12", Redact.coordinates(51.50073, -0.12463, redact = false))
        assertEquals(Redact.PLACEHOLDER, Redact.value("secret", redact = true))
        assertEquals(Redact.PLACEHOLDER, Redact.value(null, redact = false))
        assertEquals("abc", Redact.value("abc", redact = false))
    }

    @Test
    fun csvEscapesSeparatorsQuotesAndFormulas() {
        assertEquals("plain", AuditExport.csvField("plain"))
        assertEquals("\"a,b\"", AuditExport.csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", AuditExport.csvField("say \"hi\""))
        assertEquals("'=1+1", AuditExport.csvField("=1+1"))
        assertEquals("\"'-1,2\"", AuditExport.csvField("-1,2"))
    }

    @Test
    fun csvHasHeaderAndOneRowPerEvent() {
        val csv = AuditExport.toCsv(listOf(event("x, y"), event()))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(3, lines.size)
        assertEquals(AuditExport.CSV_HEADER.joinToString(","), lines[0])
        assertTrue(lines[1].startsWith("2023-11-14T22:13:20.000Z,1700000000000,com.example.target,4242,LOCATION"))
        assertTrue(lines[1].endsWith("\"x, y\""))
    }

    @Test
    fun jsonExportIsParseable() {
        val json = AuditExport.toJson(listOf(event()))
        assertEquals(1, AuditCodec.decodeBatch(json).size)
        assertFalse(json.contains("password"))
    }
}
