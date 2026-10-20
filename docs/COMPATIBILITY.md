# Compatibility matrix

## Tested environment

| Item | Value |
|---|---|
| Device | Android Emulator AVD `Pixel_6ksu`, image `android-34/google_apis_playstore/x86_64` |
| Android | 14, API 34, `user` build |
| Root | Magisk alpha (`io.github.vvb2060.magisk`) with ReZygisk |
| Hook framework | Vector 2.2 (version code 3080, JingMatrix), Xposed API 102, via its legacy-API bridge |
| Other active modules on the device | HideMyDevice (scope: `flar2.devcheck`), HMA (scope: system). Neither scoped the probe apps. |
| Module build | AppGuard 0.3.0 debug, AGP 9.2.1, Kotlin 2.2.10, targetSdk 34 |
| Test date | 2026-10-20 |

## Feature × Android version

| Feature | Interception point | API 29–30 | API 31–33 | API 34 | API 35+ |
|---|---|---|---|---|---|
| Android ID | `Settings.Secure.getString(ContentResolver, String)` | expected (public since API 3) | expected | **verified** | expected, untested |
| Device info | `android.os.Build` static fields | expected | expected | **verified** | expected, untested |
| Last-known location | `LocationManager.getLastKnownLocation(String)` | expected | expected | **verified** | expected, untested |
| Current location | `LocationManager.getCurrentLocation` (both overloads) | API 30 only (method added in 30); not hooked on 29 | expected | **verified** | expected, untested |
| Location updates | `requestLocationUpdates` (all overloads with `LocationListener`/`PendingIntent`), `removeUpdates` | expected | expected | **verified** (listener + PendingIntent) | expected, untested |
| Single update | `requestSingleUpdate` | hooked, untested | hooked, untested | hooked, untested | method deprecated; may be absent |
| Clipboard reads | `ClipboardManager` read methods | expected | expected | **verified** | expected, untested |
| Sensors | `SensorManager.getSensorList/getDefaultSensor/registerListener/requestTriggerSensor` | expected | expected | **verified** | expected, untested |
| Crash guard | default uncaught-exception handler | expected | expected | **verified** | expected, untested |

"Expected" means the hooked methods exist with the same signatures on those versions according to the public SDK. No device has confirmed it. The location hooks attach to every declared overload by name and report how many they found, for example `requestLocationUpdates=12` on API 34. A version that reshapes these methods shows up in the app's runtime status, not as a silent failure.

## Architectures

The module contains no native code, so it is ABI-independent. Testing was done on x86_64 only. arm64-v8a is expected to behave identically but has not been tested on hardware.

## Frameworks

| Framework | Status |
|---|---|
| Vector 2.2 (API 102) | Tested |
| LSPosed ≥ 1.9 (API 93+) | Expected; uses only the legacy API plus `xposedsharedprefs` |
| EdXposed, or LSPosed < API 93 | Unsupported (no new-XSharedPreferences) |
