#!/usr/bin/env bash
# Packages one region's built tiles/graph/search-index into a single <id>.rpregion archive
# (a plain tar.gz: manifest.json + tiles.mbtiles + graphhopper/ + search.db), matching the format
# RegionDownloadManager.kt extracts on-device.
#
# Usage: package-region.sh <region-id> <tiles.mbtiles> <graphhopper-dir> <search.db> <output.rpregion>
# Region metadata (displayName, bounds) is looked up from regions-catalog.csv by id.

set -euo pipefail

if [ $# -ne 5 ]; then
  echo "Usage: $0 <region-id> <tiles.mbtiles> <graphhopper-dir> <search.db> <output.rpregion>" >&2
  exit 1
fi

REGION_ID="$1"
TILES="$2"
GRAPHHOPPER_DIR="$3"
SEARCH_DB="$4"
OUTPUT="$5"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CATALOG_CSV="$SCRIPT_DIR/regions-catalog.csv"

python3 "$SCRIPT_DIR/package_region.py" \
  --catalog "$CATALOG_CSV" \
  --id "$REGION_ID" \
  --tiles "$TILES" \
  --graphhopper-dir "$GRAPHHOPPER_DIR" \
  --search-db "$SEARCH_DB" \
  --output "$OUTPUT"
