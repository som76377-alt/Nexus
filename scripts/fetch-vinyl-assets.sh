#!/usr/bin/env bash
set -euo pipefail

DISC_URL='https://img.magnific.com/free-psd/vintage-vinyl-record-with-red-label-classic-music-icon_191095-78705.jpg?semt=ais_hybrid&w=740&q=80'
ARM_URL='https://banner2.cleanpng.com/20180528/jgs/kisspng-car-plumbing-fixtures-technology-record-player-5b0bb4e5dd3d52.4504014915274938619062.jpg'

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/app/src/main/res/drawable-nodpi"
mkdir -p "$OUT_DIR"

if command -v magick >/dev/null 2>&1; then
  CONVERT=(magick)
elif command -v convert >/dev/null 2>&1; then
  CONVERT=(convert)
else
  echo "ERROR: ImageMagick (magick or convert) is required to convert the supplied JPEG downloads into PNG files." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fetch_and_convert() {
  local name="$1"
  local url="$2"
  local output="$OUT_DIR/$name"
  local download="$TMP_DIR/$name.jpg"
  local converted="$TMP_DIR/$name.png"

  echo "Downloading $name..."
  curl -fL --retry 3 --retry-delay 2 "$url" -o "$download"

  echo "Converting $name from JPEG to PNG..."
  "${CONVERT[@]}" "$download" "PNG:$converted"

  local signature
  signature="$(od -An -tx1 -N4 "$converted" | tr -d ' \n')"
  if [[ "$signature" != "89504e47" ]]; then
    echo "ERROR: $name has an invalid PNG signature (got '$signature'; expected 89504e47)." >&2
    exit 1
  fi

  local size
  size="$(wc -c < "$converted" | tr -d ' ')"
  if (( size <= 10240 )); then
    echo "ERROR: $name is too small ($size bytes); expected more than 10 KB (10240 bytes)." >&2
    exit 1
  fi

  install -m 0644 "$converted" "$output"
  echo "Verified $output ($size bytes)."
}

fetch_and_convert 'vinyl_disc.png' "$DISC_URL"
fetch_and_convert 'tonearm.png' "$ARM_URL"
