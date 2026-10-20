# Feature verification checklist

Every result below came from the probe app on the environment in
[COMPATIBILITY.md](COMPATIBILITY.md). In each case the value the app received was compared with
module-off behavior; a hook being registered was never counted as enforcement.

Setup: AppGuard scope = {AppGuard, `io.github.imkamaran.agprobe` (*target*), `io.github.imkamaran.agprobe.control` (*control*)}.
Only *target* has a policy. Config is installed with `tools/verify/setcfg.sh`, and probes run with
`tools/verify/probe.sh`. Read results with `adb logcat -s AGProbe:I`.

## Baseline (module on, no policy)

| Probe | target | control |
|---|---|---|
| `android_id` | `19d11579d10c1bf1` | `19d11579d10c1bf1` |
| `build` | `Google\|google\|sdk_gphone64_x86_64\|emu64xa\|sdk_gphone64_x86_64` | same |
| location (all paths) | `37.4220,-122.084` (emulator GPS) or null before first fix | same |

## Results

| # | Check | Config | Expected | Observed | Result |
|---|---|---|---|---|---|
| 1 | Android ID simulation | `config_simulate.json` | `47ee033178e20fbc` (SHA-256 derivation, computed independently in Python) | `47ee033178e20fbc` | ✅ |
| 2 | Android ID stable across restarts | same, 6+ process restarts | same value every time | identical in every run | ✅ |
| 3 | Device profile | `galaxy_s23` | `samsung\|samsung\|SM-S911B\|dm1q\|dm1qxxx` | identical | ✅ |
| 4 | `getLastKnownLocation` gps/network/fused, Simulate | 48.8584, 2.2945, acc 12, alt 35 | synthetic fix, correct provider | all three providers returned the fix | ✅ |
| 5 | `getCurrentLocation`, Simulate | | synthetic fix via executor | ✅ | ✅ |
| 6 | `requestLocationUpdates` + `LocationListener`, Simulate | | 1 Hz synthetic fixes, stops on `removeUpdates` | 3 fixes, then removed | ✅ |
| 7 | `requestLocationUpdates` + `PendingIntent`, Simulate | | fixes in `KEY_LOCATION_CHANGED` | 2 fixes, then removed | ✅ |
| 8 | Restrict, live switch (no restart, same pid) | `config_restrict.json` | last=null, current=null, no update fixes | as expected; Android ID back to real | ✅ |
| 9 | Kill switch, live | `config_kill.json` | ID and location real; Build fields stay (start-up only) | as expected | ✅ |
| 10 | Kill switch, after restart | | everything real; no hooks installed | as expected; no `applying policy` log line | ✅ |
| 11 | **Negative:** control app in scope without policy | any | real values everywhere, no hooks, no events | as expected in every run | ✅ |
| 12 | Corrupt policy record in shared prefs | edited as root | fail open, diagnostic log | real values; `corrupt policy record` | ✅ |
| 13 | Unsupported schema (99) | edited as root | fail open | real values; `unsupported schema 99` | ✅ |
| 14 | Corrupt companion `config.json` | truncated JSON | AppGuard starts, quarantines the file, publishes no policies | as expected; `config.corrupt.json` kept | ✅ |
| 15 | Audit delivery | | events stored with authenticated pid; hook status recorded | as expected after the visibility fix (below) | ✅ |
| 16 | Audit coalescing / one decision per call | 12 `requestLocationUpdates` overloads hooked | 1 event per app call | 2 app calls → 2 events; 3 `getLastKnownLocation` → count 3 | ✅ |
| 17 | UI → shared config | toggle Android ID mode in the editor | published record changes immediately | `ALLOW` → `SIMULATE` observed in `policies.xml` | ✅ |
| 18 | UI screens render with live data | Home, Apps, Policy editor, Audit, Settings | — | screenshots checked | ✅ |
| 19 | Sensor Restrict (motion + compass) | `config_restrict_clip_sensors.json` | list hides restricted types; default accel null; light still works | list 18 → 7; `default_accel=null`; light `returned=true` | ✅ |
| 20 | Sensor Restrict, cached `Sensor`, live switch | Allow → Restrict, same pid | registration refused | `returned=false`, 0 events (30 events under Allow) | ✅ |
| 21 | Sensor Allow = audit only | `config_sensors_audit.json` | data untouched; registrations logged | 30 events in 2 s; `registered accelerometer`/`light` events | ✅ |
| 22 | **Negative:** control app sensors | any | unchanged | 18 sensors, 30 events in 2 s | ✅ |
| 23 | Clipboard Restrict | `config_restrict_clip_sensors.json` | target reads empty; its write still works | `has=false text=null`; control then read the target's text | ✅ |
| 24 | Clipboard baseline with Allow | `config_all_allow.json` | target reads its own clip | `has=true text=agprobe-…` | ✅ |
| 25 | Crash guard trips | 3 start-up crashes (`probe=crash`) | 4th start unmodified, status reported | counter 100;1→3; real read; `suspended by crash guard` | ✅ |
| 26 | Crash guard re-arms | policy re-saved (revision 101) | policy enforced again | `has=false` | ✅ |
| 27 | Dark mode | Settings → Appearance → Dark | theme + bar icons switch; persisted | screenshots; `"ui":{"theme":"DARK"}` in private config only | ✅ |
| 28 | Network/contacts/phone baseline with Allow (`probe=privacy`) | Allow ×3 | real values | SSID `"AndroidWifi"`, 1 scan result, 1 row per provider, T-Mobile 310260, number present | ✅ |
| 29 | Wi-Fi Restrict | network Restrict | no Wi-Fi details | `<unknown ssid>`, BSSID `02:00:…`, IP 0, 0 scan results | ✅ |
| 30 | Wi-Fi Simulate | network Simulate | synthetic router; Android's own redaction kept | `"FRITZ!Box EEEB"`, `58:57:36:89:58:c6`; `getTransportInfo` still `<unknown ssid>`; 0 scans | ✅ |
| 31 | Contacts/call log/calendar Restrict | all sources | 0 rows, columns kept | 0 rows with projection (2 cols) and without (36/41/79 cols) | ✅ |
| 32 | Contacts sources, live UI change | untick Calendar in editor, same pid | calendar visible, contacts still hidden | calendar 1 row, contacts 0 rows | ✅ |
| 33 | Phone Restrict | telephony Restrict | no SIM info | empty operator/country, carrier id -1, number null, 0 subscriptions | ✅ |
| 34 | Phone Simulate | `vodafone_uk` | Vodafone UK, number hidden | 23415 / Vodafone UK / gb; number null | ✅ |
| 35 | **Negative:** control app with Allow policy | any | real values | identical to baseline in every run | ✅ |

Found and fixed during verification: on Android 11+ the audit provider was invisible to hooked apps (`Unknown authority`). AppGuard now grants each enabled package a read URI permission on its provider, which the platform documents as making the granting app visible. Grants are renewed on every publish and after boot.

## Not verified

- `requestSingleUpdate` (hooked, not called by the probe).
- `NetworkInterface.getHardwareAddress` (hooked; the emulator returns no interfaces to apps).
- `ContentResolver.query` with a `Bundle` query argument and `TelephonyManager.getGroupIdLevel1`/`getSimSpecificCarrierId*` (hooked, not called by the probe).
- `ClipboardManager.getText`/`hasText` and `SensorManager.requestTriggerSensor` (hooked, not called by the probe).
- Android 10–13 and 15+, real arm64 hardware, and LSPosed (non-Vector).
- Behavior after a device reboot. The boot-time grant renewal is implemented but untested.
- Release-build APK on the device (only the debug build was installed; the code is identical, with no minification).
- JSON/CSV export through the system file picker. The formatting is unit-tested; the SAF flow wasn't driven on the device.

## Performance (emulator, median of 7×5000 calls, 3 process runs; µs per call)

| Call | Control (unhooked) | Target, Allow, default audit | Target, Allow, verbose audit | Target, Simulate |
|---|---|---|---|---|
| `Settings.Secure.getString(android_id)` | ~15 | ~26 | ~35 | ~18 (no settings-provider lookup) |
| `getString` (other key) | 36–55 | within noise | within noise | within noise |
| `getLastKnownLocation` | 770–1400 | no measurable increase | no measurable increase | no measurable increase |

The x86_64 emulator is noisy at this scale, so treat these as orders of magnitude. Hooks for methods an app doesn't call cost nothing, and apps without a policy have no hooks at all.
