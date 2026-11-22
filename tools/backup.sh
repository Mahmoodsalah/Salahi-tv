#!/bin/bash
# Saves everything needed to undo: device info, the full app list, what is already disabled, and the settings.
# Usage: tools/backup.sh <tv-ip>     → ~/salahi-tv-backup-<date>/
set -e
D="$1:5555"; [ -n "$1" ] || { echo "usage: $0 <tv-ip>"; exit 1; }
OUT=~/salahi-tv-backup-$(date +%Y%m%d-%H%M); mkdir -p "$OUT"
adb -s "$D" shell getprop > "$OUT/props.txt"
adb -s "$D" shell pm list packages | tr -d '\r' | sed 's/package://' | sort > "$OUT/packages-all.txt"
adb -s "$D" shell pm list packages -d | tr -d '\r' | sed 's/package://' | sort > "$OUT/packages-disabled.txt"
for ns in system secure global; do adb -s "$D" shell settings list $ns > "$OUT/settings-$ns.txt"; done
# The launcher install disables the stock Home, so it is on the restore list from the start.
adb -s "$D" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
  | tail -n 1 | tr -d '\r' | cut -d/ -f1 > "$OUT/disabled-by-us.txt"
ADB_DIR=$(dirname "$(command -v adb)")
cat > "$OUT/restore.sh" <<R
#!/bin/bash
# Turns back on every app this setup disabled.
export PATH="$ADB_DIR:\$PATH"
adb connect "$D"
while read -r p; do [ -n "\$p" ] && adb -s "$D" shell pm enable --user 0 "\$p"; done < "$OUT/disabled-by-us.txt"
R
chmod +x "$OUT/restore.sh"
echo "$OUT"
