#!/usr/bin/env bash
# Builds a fixed queue of regions sequentially (smallest extract first), skipping any that already
# have a finished .rpregion. Each build-region.sh call is itself idempotent/skip-if-exists per
# pipeline stage, so a killed/resumed run picks up where it left off.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

QUEUE=(de-hh de-sl de-be de-mv de-sh de-th de-st hr de-rp de-sn de-bb si sk de-he de-ni ch at de-by de-nw it)

for ID in "${QUEUE[@]}"; do
  ARCHIVE="$REPO_ROOT/tools/data/regions/$ID.rpregion"
  if [ -f "$ARCHIVE" ]; then
    echo "=== $ID already built, skipping ==="
    continue
  fi
  echo "=== Building $ID ($(date)) ==="
  if "$SCRIPT_DIR/build-region.sh" "$ID"; then
    echo "=== $ID done ($(date)) ==="
  else
    echo "=== $ID FAILED ($(date)) - continuing with the rest ==="
  fi
done

echo "=== Queue finished ($(date)) ==="
