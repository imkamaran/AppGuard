package io.github.imkamaran.appguard.policy

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Deterministic synthetic identifiers. The same (package, seed) always yields the same value, so
 * an app sees a stable identity across calls and process restarts until the user rotates the seed.
 */
object SyntheticIds {
    /** Real ANDROID_ID values (API 26+) are 64-bit numbers rendered as 16 lowercase hex digits. */
    val ANDROID_ID_FORMAT = Regex("^[0-9a-f]{16}$")
    val SEED_FORMAT = Regex("^[0-9a-f]{32}$")

    fun newSeed(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    fun isValidSeed(seed: String): Boolean = SEED_FORMAT.matches(seed)

    fun androidId(packageName: String, seed: String): String {
        require(isValidSeed(seed)) { "invalid seed" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("appguard/android_id/v1|$packageName|$seed".toByteArray(Charsets.UTF_8))
        var id = digest.copyOf(8).toHex()
        // An all-zero id is never handed out by Android; vanishingly unlikely, but keep the contract.
        if (id == "0000000000000000") id = "0000000000000001"
        return id
    }

    fun isValidAndroidId(value: String): Boolean = ANDROID_ID_FORMAT.matches(value)

    /** A router-style network name (unquoted), e.g. "TP-Link_3F2A". */
    fun wifiSsid(packageName: String, seed: String): String {
        val d = digest("wifi_ssid", packageName, seed)
        val prefix = SSID_PREFIXES[(d[0].toInt() and 0xff) % SSID_PREFIXES.size]
        return prefix + d.copyOfRange(1, 3).toHex().uppercase()
    }

    /** A unicast, globally administered access-point MAC in Android's "aa:bb:cc:dd:ee:ff" form. */
    fun wifiBssid(packageName: String, seed: String): String {
        val d = digest("wifi_bssid", packageName, seed).copyOf(6)
        d[0] = (d[0].toInt() and 0xfc).toByte()
        return d.toHex().chunked(2).joinToString(":")
    }

    private fun digest(kind: String, packageName: String, seed: String): ByteArray {
        require(isValidSeed(seed)) { "invalid seed" }
        return MessageDigest.getInstance("SHA-256").digest("appguard/$kind/v1|$packageName|$seed".toByteArray(Charsets.UTF_8))
    }

    private val SSID_PREFIXES = listOf("TP-Link_", "NETGEAR", "Linksys", "ASUS_", "HUAWEI-", "Vodafone-", "FRITZ!Box ", "eero-")

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Internally consistent device identities for android.os.Build. Values are taken from the
 * public build properties of retail devices; fingerprint, hardware and board fields are left
 * untouched by the hooks (see README limitations).
 */
data class DeviceProfile(
    val id: String,
    val label: String,
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
) {
    fun isWellFormed(): Boolean = listOf(manufacturer, brand, model, device, product).all {
        it.isNotBlank() && it.length <= 64 && it.all { c -> c.code in 0x20..0x7e }
    }
}

object DeviceProfiles {
    const val DEFAULT_ID = "pixel_7"

    val ALL: List<DeviceProfile> = listOf(
        DeviceProfile("pixel_7", "Google Pixel 7", "Google", "google", "Pixel 7", "panther", "panther"),
        DeviceProfile("pixel_8", "Google Pixel 8", "Google", "google", "Pixel 8", "shiba", "shiba"),
        DeviceProfile("galaxy_s23", "Samsung Galaxy S23", "samsung", "samsung", "SM-S911B", "dm1q", "dm1qxxx"),
        DeviceProfile("galaxy_a54", "Samsung Galaxy A54 5G", "samsung", "samsung", "SM-A546B", "a54x", "a54xnseea"),
        DeviceProfile("moto_g54", "Motorola moto g54 5G", "motorola", "motorola", "moto g54 5G", "cancunf", "cancunf_g"),
    )

    fun byId(id: String): DeviceProfile? = ALL.firstOrNull { it.id == id }
}

/** A mobile carrier as TelephonyManager reports it: MCC+MNC, display name and ISO country. */
data class CarrierProfile(val id: String, val label: String, val mccMnc: String, val name: String, val countryIso: String) {
    fun isWellFormed(): Boolean =
        mccMnc.length in 5..6 && mccMnc.all { it.isDigit() } && name.isNotBlank() && countryIso.length == 2 && countryIso.all { it in 'a'..'z' }
}

object CarrierProfiles {
    const val DEFAULT_ID = "tmobile_us"

    val ALL: List<CarrierProfile> = listOf(
        CarrierProfile("tmobile_us", "T-Mobile (US)", "310260", "T-Mobile", "us"),
        CarrierProfile("verizon_us", "Verizon (US)", "311480", "Verizon", "us"),
        CarrierProfile("vodafone_uk", "Vodafone (UK)", "23415", "Vodafone UK", "gb"),
        CarrierProfile("telekom_de", "Telekom (Germany)", "26201", "Telekom.de", "de"),
        CarrierProfile("orange_fr", "Orange (France)", "20801", "Orange F", "fr"),
    )

    fun byId(id: String): CarrierProfile? = ALL.firstOrNull { it.id == id }
}
