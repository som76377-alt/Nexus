#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/android-release.yml"
test -f "$workflow"

expect_count() {
  local expected="$1"
  local wanted="$2"
  local count
  count="$(grep -Fxc -- "$expected" "$workflow" || true)"
  if [[ "$count" != "$wanted" ]]; then
    echo "::error::Expected $wanted occurrence(s) of: $expected (found $count)"
    exit 1
  fi
}

expect_count "        if: github.event_name == 'push' && github.ref_type == 'tag'" 1
expect_count "        if: github.event_name != 'push' || github.ref_type != 'tag'" 1
expect_count "        if: github.event_name == 'push' && github.ref_type == 'tag' && vars.NEXUS_FIREBASE_PUSH_ENABLED == 'true'" 2

if grep -Fq "if: github.ref_type == 'tag'" "$workflow"; then
  echo "::error::A tag ref alone must never publish or notify a release; workflow_dispatch can target tags."
  exit 1
fi

echo "Release publication is restricted to tag push events; manual dispatch remains validation-only."
