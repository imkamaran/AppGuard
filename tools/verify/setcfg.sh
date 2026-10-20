#!/bin/sh
# Usage: setcfg.sh <test-config.json>
# Merges a test configuration into AppGuard's canonical config (files/config.json) and restarts
# AppGuard so it validates and republishes it. Requires adb + root on the device.
#
# Safety: the device's current config is first saved to tools/verify/.backup/ (restore with
# restorecfg.sh). Only the policies named in the test file are replaced; every other app's policy
# and the UI settings are kept. "global" is taken from the test file when it has one, which
# temporarily affects all apps (e.g. the kill-switch test).
export MSYS_NO_PATHCONV=1
ADB="${ADB:-adb}"
DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="$DIR/.backup"
mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
CURRENT="$BACKUP_DIR/config-$STAMP.json"
MERGED="$BACKUP_DIR/merged-$STAMP.json"
F=/data/data/io.github.imkamaran.appguard/files/config.json

"$ADB" shell "su -c 'cat $F 2>/dev/null'" > "$CURRENT"
python -I - "$CURRENT" "$1" "$MERGED" <<'EOF' || exit 1
import json, sys
current_path, test_path, out_path = sys.argv[1:4]
try:
    with open(current_path, encoding="utf-8") as f:
        current = json.load(f)
except (OSError, ValueError):
    current = {}
with open(test_path, encoding="utf-8") as f:
    test = json.load(f)
merged = dict(current)
merged["schema"] = test.get("schema", current.get("schema", 1))
if "global" in test:
    merged["global"] = test["global"]
policies = dict(current.get("policies", {}))
policies.update(test.get("policies", {}))
merged["policies"] = policies
kept = sorted(set(policies) - set(test.get("policies", {})))
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(merged, f)
print("setcfg: backup saved; kept policies:", ", ".join(kept) or "(none)")
EOF

"$ADB" shell am force-stop io.github.imkamaran.appguard
"$ADB" push "$MERGED" /data/local/tmp/ag_config.json >/dev/null
"$ADB" shell "su -c 'D=/data/data/io.github.imkamaran.appguard; F=\$D/files/config.json; mkdir -p \$D/files; cp /data/local/tmp/ag_config.json \$F && chown \$(stat -c %u:%g \$D) \$F \$D/files && chmod 600 \$F && restorecon -R \$D/files 2>/dev/null; rm /data/local/tmp/ag_config.json'"
"$ADB" shell am start -n io.github.imkamaran.appguard/.ui.MainActivity >/dev/null
sleep 3
