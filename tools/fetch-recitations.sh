#!/bin/bash
# Saves the five recitations on this computer so the cards play them offline, with no YouTube and no ads.
# The audio stays out of git. MP3 because some TV firmware refuses M4A files.
# Usage: tools/fetch-recitations.sh
set -e
cd "$(dirname "$0")/../noor-app/app/src"
TMP=$(mktemp -d)
trap 'rm -rf "${TMP:?}"' EXIT
for item in kahf:9k1U0aGQRNA baqara:9HW7j9lZZTU sabah:fD4uDIoCn3M masa:rQnNno3Do60 ruqya:BVtCjMyJ320; do
  card=${item%%:*}; video=${item#*:}; out="$card/assets/recitation.mp3"
  if [ -s "$out" ]; then echo "$card: already there"; continue; fi
  mkdir -p "$card/assets"
  yt-dlp -q --no-warnings -f bestaudio -o "$TMP/$card.%(ext)s" "https://www.youtube.com/watch?v=$video"
  ffmpeg -nostdin -v error -y -i "$(ls "$TMP/$card".*)" -vn -ac 1 -ar 44100 -c:a libmp3lame -b:a 64k -abr 0 "$out"
  echo "$card: $(( $(wc -c < "$out") / 1000000 )) MB"
done
