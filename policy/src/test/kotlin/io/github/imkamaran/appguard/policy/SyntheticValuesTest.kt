package io.github.imkamaran.appguard.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class SyntheticValuesTest {
    private val seed = "0123456789abcdef0123456789abcdef"

    @Test
    fun androidIdHasPlatformFormat() {
        val id = SyntheticIds.androidId("com.example.a", seed)
        assertTrue(id, SyntheticIds.isValidAndroidId(id))
        assertEquals(16, id.length)
    }

    @Test
    fun androidIdIsStableForSamePackageAndSeed() {
        // Stability across process restarts follows from determinism: nothing but inputs is used.
        val first = SyntheticIds.androidId("com.example.a", seed)
        repeat(100) { assertEquals(first, SyntheticIds.androidId("com.example.a", seed)) }
        assertEquals("e11d7d1a4ebd1a2f".length, first.length)
    }

    @Test
    fun androidIdDiffersPerPackageAndPerSeed() {
        val a = SyntheticIds.androidId("com.example.a", seed)
        assertNotEquals(a, SyntheticIds.androidId("com.example.b", seed))
        assertNotEquals(a, SyntheticIds.androidId("com.example.a", "f".repeat(32)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun androidIdRejectsBadSeed() {
        SyntheticIds.androidId("com.example.a", "short")
    }

    @Test
    fun newSeedsAreValidAndDistinct() {
        val rnd = SecureRandom()
        val seeds = (1..50).map { SyntheticIds.newSeed(rnd) }.toSet()
        assertEquals(50, seeds.size)
        assertTrue(seeds.all { SyntheticIds.isValidSeed(it) })
        assertFalse(SyntheticIds.isValidSeed("0123456789ABCDEF0123456789ABCDEF"))
    }

    @Test
    fun androidIdFormatRejectsMalformedValues() {
        assertFalse(SyntheticIds.isValidAndroidId("12345"))
        assertFalse(SyntheticIds.isValidAndroidId("0123456789ABCDEF"))
        assertFalse(SyntheticIds.isValidAndroidId("0123456789abcdeg"))
    }

    @Test
    fun deviceProfilesAreWellFormedAndUnique() {
        assertTrue(DeviceProfiles.ALL.all { it.isWellFormed() })
        assertEquals(DeviceProfiles.ALL.size, DeviceProfiles.ALL.map { it.id }.toSet().size)
        assertEquals(DeviceProfiles.DEFAULT_ID, DeviceProfiles.byId(DeviceProfiles.DEFAULT_ID)!!.id)
        assertNull(DeviceProfiles.byId("missing"))
    }

    @Test
    fun locationBoundariesAreInclusive() {
        assertTrue(LocationSpec.validate(90.0, 180.0, null, null).isEmpty())
        assertTrue(LocationSpec.validate(-90.0, -180.0, 0.1f, -500.0).isEmpty())
        assertTrue(LocationSpec.validate(0.0, 0.0, 10_000f, 10_000.0).isEmpty())
    }

    @Test
    fun locationRejectsOutOfRangeAndNonFinite() {
        assertEquals(1, LocationSpec.validate(90.0001, 0.0, null, null).size)
        assertEquals(1, LocationSpec.validate(0.0, -180.5, null, null).size)
        assertEquals(2, LocationSpec.validate(Double.NaN, Double.POSITIVE_INFINITY, null, null).size)
        assertEquals(1, LocationSpec.validate(0.0, 0.0, 0f, null).size)
        assertEquals(1, LocationSpec.validate(0.0, 0.0, Float.NaN, null).size)
        assertEquals(1, LocationSpec.validate(0.0, 0.0, null, 9_000_000.0).size)
    }

    @Test
    fun syntheticLocationSpecIsBuiltFromPolicy() {
        val spec = (LocationSpec.from(LocationPolicy(Mode.SIMULATE, 35.6586, 139.7454, null, 333.0)) as Validated.Valid).value
        assertEquals(35.6586, spec.latitude, 0.0)
        assertEquals(139.7454, spec.longitude, 0.0)
        assertEquals(LocationSpec.DEFAULT_ACCURACY_M, spec.accuracyMeters, 0f)
        assertEquals(333.0, spec.altitudeMeters!!, 0.0)
        assertTrue(LocationSpec.from(LocationPolicy(Mode.SIMULATE, -91.0, 0.0)) is Validated.Invalid)
    }

    @Test
    fun parsesCoordinatePairs() {
        assertEquals(51.5007 to -0.1246, LocationSpec.parsePair("51.5007, -0.1246"))
        assertEquals(1.0 to 2.0, LocationSpec.parsePair("1 2"))
        assertNull(LocationSpec.parsePair("51.5"))
        assertNull(LocationSpec.parsePair("a,b"))
    }
}
