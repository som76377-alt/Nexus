#!/usr/bin/env bash
set -euo pipefail

DEBUG_CLASSES="app/build/tmp/kotlin-classes/debug"
RELEASE_CLASSES="app/build/tmp/kotlin-classes/release"

if [[ ! -d "$DEBUG_CLASSES" || ! -d "$RELEASE_CLASSES" ]]; then
  echo "::error::Debug and release Kotlin class outputs are required for the Provider Lab build-type check."
  exit 1
fi

if ! find "$DEBUG_CLASSES" -type f -name '*DebugProviderLabChaos*' | grep -q .; then
  echo "::error::Debug build is missing the Provider Lab chaos implementation."
  exit 1
fi

if ! find "$RELEASE_CLASSES" -type f -name 'ProviderLabChaosFactory.class' | grep -q .; then
  echo "::error::Release build is missing the no-op Provider Lab factory."
  exit 1
fi

if grep -aR -E 'DebugProviderLabChaos|Timed403DataSource|ForcedStream403Exception' "$RELEASE_CLASSES"; then
  echo "::error::Debug-only chaos implementation leaked into release classes."
  exit 1
fi

echo "Provider Lab chaos is present in debug and absent from release classes."
