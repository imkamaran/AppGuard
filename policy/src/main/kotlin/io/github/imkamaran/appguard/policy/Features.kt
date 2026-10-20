package io.github.imkamaran.appguard.policy

/**
 * Build-time knowledge of what each feature does. The UI combines this with runtime hook status
 * reported by hooked processes; neither alone is shown as "working".
 */
enum class Implementation {
    /** Hooks exist and enforcement was checked against a probe app on a real runtime. */
    VERIFIED,
    /** Hooks exist but enforcement has not been checked on a device yet. */
    UNVERIFIED,
    /** No hooks in this build; the UI control is disabled. */
    NOT_IMPLEMENTED,
}

/** Probe-app verification environment; see docs/VERIFICATION.md. */
private const val VERIFIED_ON = "Android 14 (API 34) x86_64 emulator, Vector 2.2 (Xposed API 102), probe app"

enum class Feature(
    val category: Category,
    val title: String,
    val implementation: Implementation,
    /** Where the change is made in the target process. */
    val interceptionPoints: List<String>,
    val limitations: List<String>,
    /** Where verification was done, when [implementation] is VERIFIED. */
    val verifiedOn: String = "",
) {
    ANDROID_ID(
        Category.IDENTIFIERS,
        "Android ID",
        Implementation.VERIFIED,
        listOf("Settings.Secure.getString(ContentResolver, \"android_id\")"),
        listOf(
            "Reads that bypass the public helper (ContentResolver.query/call on the settings provider, Settings.Secure.getStringForUser, native code) see the real value.",
            "Android 8+ already scopes ANDROID_ID per signing key and user; this replaces that already-scoped value.",
        ),
        VERIFIED_ON,
    ),
    DEVICE_INFO(
        Category.IDENTIFIERS,
        "Device model & manufacturer",
        Implementation.VERIFIED,
        listOf("android.os.Build.MANUFACTURER, BRAND, MODEL, DEVICE, PRODUCT static fields (set at process start)"),
        listOf(
            "Build.FINGERPRINT, HARDWARE, BOARD and SystemProperties (getprop, native __system_property_get) still report the real device.",
            "Changes apply when the app process starts; restart the app after editing.",
            "WebView user agents and server-side fingerprinting are not affected.",
        ),
        VERIFIED_ON,
    ),
    LOCATION(
        Category.LOCATION,
        "Location (LocationManager)",
        Implementation.VERIFIED,
        listOf(
            "LocationManager.getLastKnownLocation",
            "LocationManager.getCurrentLocation (API 30+)",
            "LocationManager.requestLocationUpdates with a LocationListener or PendingIntent",
            "LocationManager.removeUpdates",
            "LocationManager.requestSingleUpdate (hooked; not exercised by the probe app)",
        ),
        listOf(
            "Google Play services FusedLocationProviderClient is not intercepted; most apps use it.",
            "GNSS status/measurements, NMEA, Wi-Fi/cell scans and IP geolocation can still reveal position.",
            "Native code and other processes are not affected. Emergency location (system services) is never touched.",
            "Simulation does not mark fixes as mock; Restrict delivers no fixes instead of an error.",
        ),
        VERIFIED_ON,
    ),
    CLIPBOARD(
        Category.CLIPBOARD,
        "Clipboard reads",
        Implementation.VERIFIED,
        listOf(
            "ClipboardManager.getPrimaryClip / getPrimaryClipDescription / getText (read side only)",
            "ClipboardManager.hasPrimaryClip / hasText",
        ),
        listOf(
            "Writes (setPrimaryClip/setText) are never affected.",
            "Restrict answers in the app process, so the system never sends the clip and shows no paste notice.",
            "Paste through the keyboard or a text field's context menu is performed by the system and the IME, not the app's ClipboardManager calls, and still works.",
            "Clipboard contents are never written to the audit log.",
        ),
        VERIFIED_ON,
    ),
    SENSORS(
        Category.SENSORS,
        "Sensors",
        Implementation.VERIFIED,
        listOf(
            "SensorManager.getSensorList / getDefaultSensor (restricted types removed)",
            "SensorManager.registerListener / requestTriggerSensor (restricted types refused, returns false)",
        ),
        listOf(
            "Allow is audit only: registrations are logged, sensor data is never changed.",
            "The legacy SensorListener API (bitmask overloads, deprecated since API 3) is logged but not filtered.",
            "Native ASensorManager (NDK), WebView/JavaScript device-motion events and other processes are not affected.",
            "Sensor objects obtained before a policy change are still refused at registration.",
        ),
        VERIFIED_ON,
    ),
    NETWORK(
        Category.NETWORK,
        "Wi-Fi network identity",
        Implementation.VERIFIED,
        listOf(
            "WifiInfo.getSSID / getBSSID / getMacAddress / getIpAddress (covers WifiManager.getConnectionInfo and NetworkCapabilities.getTransportInfo)",
            "WifiManager.getScanResults (nearby networks)",
            "NetworkInterface.getHardwareAddress (hooked; the verification emulator exposes no interfaces to apps)",
        ),
        listOf(
            "Restrict reports what Android shows apps without location permission: \"<unknown ssid>\", BSSID 02:00:00:00:00:00, no scan results.",
            "Simulate reports a stable synthetic router name and BSSID from the identity seed; nearby-network scans are empty, the IP address is unchanged.",
            "Connectivity itself is never changed. The public IP address, DNS and server-side checks are not affected.",
            "Native code (ioctl/netlink) and WifiManager callbacks that deliver ScanResult objects directly are not filtered.",
        ),
        VERIFIED_ON,
    ),
    CONTACTS(
        Category.CONTACTS,
        "Contacts, call log & calendar",
        Implementation.VERIFIED,
        listOf("ContentResolver.query for the contacts, call log and calendar providers (all overloads)"),
        listOf(
            "Restricted queries return an empty cursor with the requested columns, as if there were no entries; the permission is not revoked.",
            "Writes (insert/update/delete), ContentProviderClient queries and the system contact picker are not affected.",
            "Data the app copied earlier, or receives from a server, is not affected.",
        ),
        VERIFIED_ON,
    ),
    TELEPHONY(
        Category.TELEPHONY,
        "Phone & SIM info",
        Implementation.VERIFIED,
        listOf(
            "TelephonyManager: SIM/network operator, operator name and country, phone number, GID1, carrier id",
            "SubscriptionManager.getActiveSubscriptionInfoList / getActiveSubscriptionInfo / ForSimSlotIndex",
        ),
        listOf(
            "Restrict looks like no SIM: empty operator and country, no phone number, no subscriptions. SIM state, calls and data still work.",
            "Simulate reports the chosen carrier's MCC/MNC, name and country; the phone number stays hidden.",
            "Subscription lists (SubscriptionManager) are empty in both modes, since each record carries the real carrier and number.",
            "IMEI, IMSI and SIM serial are already blocked for normal apps since Android 10 and are not hooked.",
            "Country can still be inferred from the locale, time zone, IP address or location.",
        ),
        VERIFIED_ON,
    ),
    CRASH_GUARD(
        Category.SYSTEM,
        "Crash-loop protection",
        Implementation.VERIFIED,
        listOf("Default uncaught-exception handler of the app process (chained, never replaced)"),
        listOf(
            "After 3 consecutive crashes within 60 s of start-up, the app's policy is suspended until it is saved again.",
            "Native crashes and ANRs are not counted.",
            "Stores a small counter file in the app's own no_backup directory.",
        ),
        VERIFIED_ON,
    ),
    ;

    companion object {
        fun forCategory(category: Category): List<Feature> = entries.filter { it.category == category }

        /** Stable ids hooked processes use when reporting install status. */
        fun byId(id: String): Feature? = entries.firstOrNull { it.name == id }
    }
}
