#!/usr/bin/env bash
set -euo pipefail

DISC_URL="${DISC_URL:-}"
ARM_URL="${ARM_URL:-}"

if [[ -z "$DISC_URL" ]]; then
  DISC_URL='https://img.magnific.com/free-psd/vintage-vinyl-record-with-red-label-classic-music-icon_191095-78705.jpg?semt=ais_hybrid&w=740&q=80'
  echo "DISC_URL is empty; using default URL."
fi
if [[ -z "$ARM_URL" ]]; then
  ARM_URL='https://banner2.cleanpng.com/20180528/jgs/kisspng-car-plumbing-fixtures-technology-record-player-5b0bb4e5dd3d52.4504014915274938619062.jpg'
  echo "ARM_URL is empty; using default URL."
fi
export DISC_URL ARM_URL
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

if command -v identify >/dev/null 2>&1; then
  IDENTIFY=(identify)
elif [[ "${CONVERT[0]}" == "magick" ]]; then
  IDENTIFY=(magick identify)
else
  echo "ERROR: ImageMagick identify is required to verify the generated PNG files." >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

verify_and_install() {
  local name="$1"
  local converted="$2"
  local output="$OUT_DIR/$name"

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

  local details channels dimensions
  details="$("${IDENTIFY[@]}" -format '%[channels] %wx%h' "$converted")"
  read -r channels dimensions <<< "$details"
  if [[ "$channels" != *a* ]]; then
    echo "ERROR: $name has no alpha channel (identify reported '$channels'; expected srgba)." >&2
    exit 1
  fi

  install -m 0644 "$converted" "$output"
  echo "Verified $output: $channels $dimensions ($size bytes)."
}

fetch_disc() {
  local download="$TMP_DIR/vinyl_disc.jpg"
  local trimmed="$TMP_DIR/vinyl_disc_trimmed.png"
  local cropped="$TMP_DIR/vinyl_disc_cropped.png"
  local mask="$TMP_DIR/vinyl_disc_mask.png"
  local converted="$TMP_DIR/vinyl_disc.png"

  echo "Downloading vinyl_disc.png..."
  local referer
  referer="$(printf '%s' "$DISC_URL" | sed -E 's#^(https?://[^/]+).*#\1/#')"
  curl -fL --retry 3 --retry-delay 2 -A "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36" -e "$referer" -H "Accept: image/*" "$DISC_URL" -o "$download"

  echo "Trimming, square-cropping, resizing, and masking vinyl_disc.png..."
  "${CONVERT[@]}" "$download" -trim +repage "$trimmed"
  local square
  square="$("${IDENTIFY[@]}" -format '%[fx:min(w,h)]' "$trimmed")"
  "${CONVERT[@]}" "$trimmed" -gravity center -crop "${square}x${square}+0+0" +repage -resize 1024x1024 "$cropped"
  "${CONVERT[@]}" -size 1024x1024 xc:none -fill white -draw 'circle 512,512 512,0' "$mask"
  "${CONVERT[@]}" "$cropped" "$mask" -alpha set -compose DstIn -composite "PNG:$converted"

  verify_and_install "vinyl_disc.png" "$converted"
}

fetch_tonearm() {
  local download="$TMP_DIR/tonearm.jpg"
  local converted="$TMP_DIR/tonearm.png"

  echo "Downloading tonearm.png..."
  local referer
  referer="$(printf '%s' "$ARM_URL" | sed -E 's#^(https?://[^/]+).*#\1/#')"
  curl -fL --retry 3 --retry-delay 2 -A "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36" -e "$referer" -H "Accept: image/*" "$ARM_URL" -o "$download"

  echo "Removing corner-connected background from tonearm.png..."
  local width height
  read -r width height <<< "$("${IDENTIFY[@]}" -format '%w %h' "$download")"
  "${CONVERT[@]}" "$download" \
    -alpha set -fuzz 12% -fill none \
    -draw 'color 0,0 floodfill' \
    -draw "color $((width - 1)),0 floodfill" \
    -draw "color 0,$((height - 1)) floodfill" \
    -draw "color $((width - 1)),$((height - 1)) floodfill" \
    -trim +repage "PNG:$converted"

  verify_and_install "tonearm.png" "$converted"
}

if [[ "${1:-}" == "--fetch-disc" ]]; then
  fetch_disc
  exit $?
elif [[ "${1:-}" == "--fetch-tonearm" ]]; then
  fetch_tonearm
  exit $?
elif [[ $# -ne 0 ]]; then
  echo "ERROR: Unknown argument: $1" >&2
  exit 2
fi

run_asset() {
  local name="$1"
  local mode="$2"
  local log="$TMP_DIR/$name.log"
  if bash "$0" "$mode" >"$log" 2>&1; then
    printf '%s: OK\n' "$name"
    return 0
  fi

  local reason
  reason="$(grep -m1 -E '^(ERROR:|curl:|convert:|identify:|install:|sed:|od:|wc:)' "$log" || true)"
  if [[ -z "$reason" ]]; then
    reason="$(tail -n 1 "$log" || true)"
  fi
  if [[ -z "$reason" ]]; then
    reason="asset processing failed without an error message"
  fi
  printf '%s: FAILED: %s\n' "$name" "$reason"
  return 1
}

failed=0
run_asset "vinyl_disc.png" "--fetch-disc" || failed=1
run_asset "tonearm.png" "--fetch-tonearm" || failed=1

if (( failed != 0 )); then
  exit 1
fi
