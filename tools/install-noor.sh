#!/bin/bash
# Builds the five recitation cards (Kahf, Baqarah, morning and evening adhkar, ruqyah) and installs them on the TV.
# The Kahf app also carries the reminders and the adhan, so it gets the overlay and usage-access permissions
# and is started once.
# Usage: tools/install-noor.sh <tv-ip>
set -e
D="$1:5555"; [ -n "$1" ] || { echo "usage: $0 <tv-ip>"; exit 1; }
cd "$(dirname "$0")/../salahi-app"
for f in kahf baqara sabah masa ruqya; do
  [ -s "app/src/$f/assets/recitation.mp3" ] || { echo "missing $f audio: run tools/fetch-recitations.sh first"; exit 1; }
done
./gradlew -q assembleRelease
for f in kahf baqara sabah masa ruqya; do
  adb -s "$D" install -r "app/build/outputs/apk/$f/release/app-$f-release.apk" >/dev/null || { echo "⛔ $f did not install"; exit 1; }
  echo "✅ $f"
done
adb -s "$D" shell appops set app.noortv.kahf SYSTEM_ALERT_WINDOW allow
adb -s "$D" shell appops set app.noortv.kahf GET_USAGE_STATS allow
# Some TVs (TCL among them) block apps from starting at boot, which would lose the adhan alarm after a restart.
adb -s "$D" shell 'cmd appops set app.noortv.kahf APP_AUTO_START allow 2>/dev/null; appops write-settings 2>/dev/null' || true
sleep 8 # Android restarts the app right after an update, so start it once that has settled
adb -s "$D" shell am broadcast -n app.noortv.kahf/app.noortv.Reminder >/dev/null
