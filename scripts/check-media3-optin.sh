#!/usr/bin/env bash
set -euo pipefail

failed=0
while IFS= read -r -d '' file; do
  if grep -qE 'import[[:space:]]+kotlin\.OptIn([[:space:]]|$)' "$file" &&
     grep -q 'UnstableApi' "$file"; then
    line=$(grep -nE 'import[[:space:]]+kotlin\.OptIn([[:space:]]|$)' "$file" | head -n1 | cut -d: -f1)
    echo "Media3 opt-in violation: $file:$line uses kotlin.OptIn with UnstableApi; import androidx.annotation.OptIn." >&2
    failed=1
  fi
  if grep -qE '@OptIn[[:space:]]*\([^)]*UnstableApi' "$file" &&
     grep -qE 'import[[:space:]]+androidx\.media3\.' "$file" &&
     ! grep -qE 'import[[:space:]]+androidx\.annotation\.OptIn([[:space:]]|$)' "$file"; then
    line=$(grep -nE '@OptIn[[:space:]]*\([^)]*UnstableApi' "$file" | head -n1 | cut -d: -f1)
    echo "Media3 opt-in violation: $file:$line uses @OptIn(UnstableApi) without androidx.annotation.OptIn." >&2
    failed=1
  fi
done < <(find app/src -type f -name '*.kt' -print0)

if [ "$failed" -ne 0 ]; then exit 1; fi
echo "Media3 opt-in guard passed."
