#!/bin/sh
# Usage: restorecfg.sh [backup.json]
# Restores AppGuard's config exactly as saved by setcfg.sh. Pass a backup file, or omit it to use
# the most recent tools/verify/.backup/config-*.json.
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-$(ls -1 "$DIR"/.backup/config-*.json 2>/dev/null | tail -1)}"
[ -s "$SRC" ] || { echo "restorecfg: no backup found"; exit 1; }
echo "restorecfg: restoring $SRC"
"$ADB" shell am force-stop io.github.imkamaran.appguard
"$ADB" push "$SRC" /data/local/tmp/ag_config.json >/dev/null
"$ADB" shell "su -c 'D=/data/data/io.github.imkamaran.appguard; F=\$D/files/config.json; cp /data/local/tmp/ag_config.json \$F && chown \$(stat -c %u:%g \$D) \$F && chmod 600 \$F && restorecon \$F 2>/dev/null; rm /data/local/tmp/ag_config.json'"
"$ADB" shell am start -n io.github.imkamaran.appguard/.ui.MainActivity >/dev/null
