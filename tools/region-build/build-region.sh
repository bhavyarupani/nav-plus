#!/usr/bin/env bash
# One-command pipeline for building a single region's downloadable package end to end:
# Geofabrik extract -> Planetiler tiles -> GraphHopper graph -> search index -> .rpregion archive.
# Does NOT upload - run upload-release.sh separately once you've verified the package on-device
# (matching this project's established rigor: verify on real hardware before publishing).
#
# Usage: build-region.sh <region-id>
# Example: build-region.sh de-bw

set -euo pipefail

if [ $# -ne 1 ]; then
  echo "Usage: $0 <region-id>" >&2
  echo "Region ids come from regions-catalog.csv, e.g. de-bw (Baden-Württemberg)" >&2
  exit 1
fi

REGION_ID="$1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
CATALOG_CSV="$SCRIPT_DIR/regions-catalog.csv"

SLUG="$(awk -F, -v id="$REGION_ID" 'NR>1 && $1==id {print $3}' "$CATALOG_CSV")"
if [ -z "$SLUG" ]; then
  echo "Region '$REGION_ID' not found in $CATALOG_CSV" >&2
  exit 1
fi

WORK_DIR="$REPO_ROOT/tools/data/regions/$REGION_ID"
mkdir -p "$WORK_DIR"
OSM_PBF="$WORK_DIR/$REGION_ID.osm.pbf"
MBTILES="$WORK_DIR/tiles.mbtiles"
GRAPHHOPPER_DIR="$WORK_DIR/graphhopper"
SEARCH_DB="$WORK_DIR/search.db"
OUTPUT_ARCHIVE="$REPO_ROOT/tools/data/regions/$REGION_ID.rpregion"

echo "=== [1/5] Fetching $SLUG extract ==="
if [ -f "$OSM_PBF" ]; then
  echo "Already downloaded: $OSM_PBF"
else
  "$SCRIPT_DIR/fetch-extract.sh" "$SLUG" "$OSM_PBF"
fi

echo "=== [2/5] Building vector tiles (Planetiler) ==="
if [ -f "$MBTILES" ]; then
  echo "Already built: $MBTILES"
else
  (
    cd "$REPO_ROOT/tools"
    java -jar planetiler/planetiler.jar --osm_path="$OSM_PBF" --output="$MBTILES" --force
  )
fi

echo "=== [3/5] Importing GraphHopper routing graph ==="
if [ -d "$GRAPHHOPPER_DIR" ] && [ -f "$GRAPHHOPPER_DIR/properties" ]; then
  echo "Already built: $GRAPHHOPPER_DIR"
else
  "$SCRIPT_DIR/import-graphhopper.sh" "$OSM_PBF" "$GRAPHHOPPER_DIR"
fi

echo "=== [4/5] Building search index ==="
if [ -f "$SEARCH_DB" ]; then
  echo "Already built: $SEARCH_DB"
else
  "$SCRIPT_DIR/build-search-index.sh" "$OSM_PBF" "$SEARCH_DB"
fi

echo "=== [5/5] Packaging .rpregion archive ==="
"$SCRIPT_DIR/package-region.sh" "$REGION_ID" "$MBTILES" "$GRAPHHOPPER_DIR" "$SEARCH_DB" "$OUTPUT_ARCHIVE"

echo
echo "Built $OUTPUT_ARCHIVE"
echo "Next: verify it on a real device, then run upload-release.sh $OUTPUT_ARCHIVE"
