#!/bin/bash
# Downloads the adhan you chose (a YouTube link, best from the muezzin's own channel) into the Kahf app,
# trimmed and at a gentle loudness, plus its opening phrase for HDMI. Pass "fajr" for the Fajr recording.
# Without a recording the app still shows the prayer banner, just silently.
# Usage: tools/fetch-adhan.sh <youtube-url> [fajr]
set -e
[ -n "$1" ] || { echo "usage: $0 <youtube-url> [fajr]"; exit 1; }
cd "$(dirname "$0")/.."
name=adhan; [ "$2" = fajr ] && name=adhan_fajr
raw=salahi-app/app/src/kahf/res/raw
TMP=$(mktemp -d)
trap 'rm -rf "${TMP:?}"' EXIT
yt-dlp -q -x --audio-format mp3 -o "$TMP/a.%(ext)s" "$1"
mkdir -p "$raw"
ffmpeg -nostdin -v error -y -i "$TMP/a.mp3" -af "silenceremove=start_periods=1:start_threshold=-45dB,loudnorm=I=-18:TP=-2" \
  -ar 44100 -ac 1 -b:a 96k "$raw/$name.mp3"
pause=$(ffmpeg -nostdin -hide_banner -t 40 -i "$raw/$name.mp3" -af silencedetect=noise=-28dB:d=0.5 -f null - 2>&1 \
  | sed -n 's/.*silence_start: \([0-9.]*\).*/\1/p' | awk '$1 > 4 {print; exit}')
[ -n "$pause" ] || pause=12
fade=$(awk "BEGIN {print $pause - 0.3}")
ffmpeg -nostdin -v error -y -i "$raw/$name.mp3" -t "$pause" -af "afade=t=out:st=$fade:d=0.3" \
  -ar 44100 -ac 1 -b:a 96k "$raw/${name}_clip.mp3"
echo "$name: $(ffprobe -v error -show_entries format=duration -of csv=p=0 "$raw/$name.mp3" | cut -d. -f1) s, opening ${pause%.*} s"
