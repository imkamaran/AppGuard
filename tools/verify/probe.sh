#!/bin/sh
# Usage: probe.sh <package> <probe> [restart]
# Runs a probe in the AppGuard probe app (all, android_id, build, last, current, updates,
# updates_pi, bench). Results: adb logcat -s AGProbe:I. With "restart" the app is force-stopped
# first, so start-up hooks are re-evaluated.
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
[ "$3" = "restart" ] && "$ADB" shell am force-stop "$1"
"$ADB" shell am start -n "$1/io.github.imkamaran.agprobe.ProbeActivity" --es probe "$2" >/dev/null
