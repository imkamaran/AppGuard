# Known limitations and unsupported APIs

AppGuard runs inside the target app's process and changes what selected Java framework APIs return
there. Anything that does not go through those methods in that process is not affected.

## General

- **Process-local only.** Native code (JNI, `libc`, direct binder transactions), other processes the app talks to (Play services, system services, its own `:remote` processes in scope or not), and servers are unaffected.
- **Requires scope.** A policy only applies if the app is also in AppGuard's LSPosed/Vector scope.
- **Start-up evaluation.** Hooks are installed only if the app has an enabled policy when its process starts. After enabling a policy for the first time, restart the app.
- **Detectability.** Hooking frameworks and AppGuard's presence can be detected by determined apps. Audit delivery makes AppGuard visible to apps with an enabled policy (see the package visibility note below). AppGuard is a privacy tool, not an anti-detection tool.
- **Package visibility side effect.** To deliver audit events, AppGuard grants each app with an enabled policy a read URI permission on its audit provider. That makes the AppGuard package visible to those apps. The provider returns no data to queries and only accepts audit events for the caller's own package.

## Android ID

- Hooked: `Settings.Secure.getString(ContentResolver, "android_id")`.
- Not covered: `ContentResolver.query()`/`call()` on the settings provider, `Settings.Secure.getStringForUser` (hidden), `Settings.System`/`Global` lookups of other identifiers, native reads.
- Android 8+ already gives each signing key and user a distinct ANDROID_ID; simulation replaces this already-scoped value with a stable synthetic one derived from a per-app seed. "Rotate identity" changes it.
- Restrict is not offered: there is no meaningful "no ID" response apps expect.

## Device info

- Hooked: `Build.MANUFACTURER`, `BRAND`, `MODEL`, `DEVICE`, `PRODUCT`, set once when the process starts.
- Not covered: `Build.FINGERPRINT`, `HARDWARE`, `BOARD`, `DISPLAY`, `SystemProperties` (`ro.product.*`), `getprop`, WebView user agent, GPU/CPU strings, and server-side fingerprinting. The partial profile can therefore look inconsistent to apps that cross-check.

## Location

- Hooked: `LocationManager.getLastKnownLocation`, `getCurrentLocation` (API 30+), `requestLocationUpdates` and `requestSingleUpdate` (LocationListener / PendingIntent forms), and `removeUpdates`.
- **Not covered: Google Play services `FusedLocationProviderClient`**, which most apps use. Its fixes are computed in the Play services process. Intercepting it in the app process (`LocationResult`, `Task<Location>`) is planned work and is not done.
- Not covered: GNSS status/measurements/NMEA listeners, Wi-Fi and cell scan results, IP geolocation, `Geocoder`, and photo EXIF data.
- Simulated fixes are not flagged as mock (`isMock() == false`). Restrict delivers nothing; it does not raise an error or report the provider as disabled.
- Simulation never changes system location settings or the device's real location. System services (emergency location, `com.android.phone`, `system_server`) are on a hard-coded never-hook list.

## Clipboard

- Hooked (read side only): `ClipboardManager.getPrimaryClip`, `getPrimaryClipDescription`, `getText`, `hasPrimaryClip`, `hasText`. Restrict makes the app see an empty clipboard.
- Never affected: writes (`setPrimaryClip`, `setText`, `clearPrimaryClip`). Pasting through the keyboard or a text field's context menu is done by the system and the IME, so it keeps working.
- Not covered: native code, and a `ClipboardManager` binder used directly through hidden APIs.
- Android 10+ independently blocks clipboard reads by apps that are not in the foreground with focus.
- Clipboard contents are never inspected or logged.

## Sensors

- Allow is **audit only**: registrations are logged when auditing is on, and data is never changed.
- Restrict hides the selected groups. They are removed from `getSensorList`, `getDefaultSensor` returns null, and `registerListener`/`requestTriggerSensor` return `false` (also for `Sensor` objects obtained earlier).
- Not covered: the NDK `ASensorManager`, WebView/JavaScript `devicemotion`/`deviceorientation` events, `getDynamicSensorList`, and the deprecated `SensorListener` bitmask API (logged, not filtered).
- No sensor-data simulation is offered.

## Wi-Fi network identity

- Restrict reports what Android shows apps without location permission: SSID `<unknown ssid>`, BSSID and MAC `02:00:00:00:00:00`, IP `0`, and no scan results.
- Simulate reports a stable, made-up router name and BSSID derived from the app's identity seed (rotating the identity changes them). Values Android has already redacted, such as `NetworkCapabilities` copies without location info, stay redacted, so the spoofing is not given away. Scans are empty and the IP address is unchanged.
- Connectivity is never changed. The public IP, DNS, native code (ioctl/netlink) and scan-result callbacks are not covered.
- `NetworkInterface.getHardwareAddress` is hooked, but the verification emulator exposes no interfaces to apps, so that path is unverified.

## Contacts, call log & calendar

- Restrict answers `ContentResolver.query` for the selected providers (`com.android.contacts`, `contacts`, `call_log`, `com.android.calendar`) with an empty cursor. Requested columns are kept so column lookups still work. Without a projection, the real query runs, only its column names are kept, and the cursor is closed before any row is read.
- The runtime permission is not revoked. Writes, `ContentProviderClient` queries, the system contact picker, and data the app copied earlier are not affected.
- An app without the permission gets an empty result instead of a `SecurityException` while Restrict is on.

## Phone & SIM info

- Restrict looks like no SIM is inserted: empty operator, operator name and country, no phone number or GID1, unknown carrier id, and no active subscriptions. SIM state, calls and mobile data are untouched.
- Simulate reports one of the built-in carrier profiles (MCC/MNC, name, country). The phone number stays hidden and the subscription list is empty, since each record carries the real carrier and number.
- Country can still be inferred from the locale, time zone, IP address or location.

## Crash-loop protection

- Only uncaught Java exceptions within 60 s of start-up count. Native crashes and ANRs do not.
- The policy is suspended after 3 consecutive early crashes, whatever the cause, including bugs in the app itself. Saving the policy again re-arms it.
- The counter is a small file in the app's own `no_backup` directory, readable by that app.

## Restricted identifiers (not offered)

IMEI/MEID, the hardware serial, and the IMSI/ICCID are behind `READ_PRIVILEGED_PHONE_STATE` for ordinary apps since Android 10. AppGuard makes no claims about them.

## Reliability

- Configuration changes reach running apps within about 2 s, polled on the app's own thread when a hooked API is called. A changed file triggers one synchronous re-read of a few KB.
- Besides the automatic crash guard, recovery is manual: switch the app's policy off, use the kill switch, or disable the module in the manager. All three take effect at the app's next start at the latest.
