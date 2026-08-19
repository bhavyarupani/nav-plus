#!/usr/bin/env bash
# Uploads a packaged .rpregion as a GitHub Releases asset (creating the "regions-v1" release the
# first time) and updates+commits regions.json's entry for it - the step that actually makes a
# newly-built region show up in the app's catalog. Requires `gh` authenticated (gh auth status).
#
# Usage: upload-release.sh <path/to/id.rpregion>

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <path/to/id.rpregion>" >&2
  exit 1
fi

ARCHIVE="$1"
REGION_ID="$(basename "$ARCHIVE" .rpregion)"
REPO="bhavyarupani/roadpulse"
RELEASE_TAG="regions-v1"
REPO_ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)"
REGIONS_JSON="$REPO_ROOT/regions.json"

if ! gh auth status >/dev/null 2>&1; then
  echo "gh is not authenticated - run 'gh auth login' first" >&2
  exit 1
fi

if ! gh release view "$RELEASE_TAG" --repo "$REPO" >/dev/null 2>&1; then
  echo "Creating release $RELEASE_TAG on $REPO"
  gh release create "$RELEASE_TAG" --repo "$REPO" --title "Region packages v1" \
    --notes "Downloadable region packages for RoadPulse's offline map/routing/search data. See tools/region-build/."
fi

echo "Uploading $ARCHIVE to $REPO release $RELEASE_TAG"
gh release upload "$RELEASE_TAG" "$ARCHIVE" --repo "$REPO" --clobber

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/update_catalog_json.py" \
  --catalog-csv "$SCRIPT_DIR/regions-catalog.csv" \
  --archive "$ARCHIVE" \
  --region-id "$REGION_ID" \
  --regions-json "$REGIONS_JSON" \
  --release-tag "$RELEASE_TAG" \
  --repo "$REPO"

echo "Updated $REGIONS_JSON - review and commit/push it yourself:"
echo "  git -C \"$REPO_ROOT\" add regions.json"
echo "  git -C \"$REPO_ROOT\" commit -m \"Add $REGION_ID to region catalog\""
echo "  git -C \"$REPO_ROOT\" push"
