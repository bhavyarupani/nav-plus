#!/usr/bin/env bash
# Downloads a Geofabrik .osm.pbf extract for one region, verified against Geofabrik's own
# published MD5 sum - the same raw-data source already used for the Bremen extract (see
# ZERO_COST_ARCHITECTURE.md). Geofabrik hosts these for free, unlimited, as their own public
# service - this is the one network dependency in the whole region-build pipeline that isn't
# our own infrastructure.
#
# Usage: fetch-extract.sh <geofabrik-path> <output.osm.pbf>
# geofabrik-path is the full relative path under https://download.geofabrik.de/, without the
# "-latest.osm.pbf" suffix - e.g. "europe/germany/baden-wuerttemberg" for a German state or
# "europe/austria" for a whole country, matching regions-catalog.csv's geofabrikPath column.
# Example: fetch-extract.sh europe/germany/baden-wuerttemberg tools/data/de-bw.osm.pbf

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <geofabrik-path> <output.osm.pbf>" >&2
  exit 1
fi

GEOFABRIK_PATH="$1"
OUTPUT="$2"
URL="https://download.geofabrik.de/${GEOFABRIK_PATH}-latest.osm.pbf"
MD5_URL="${URL}.md5"

mkdir -p "$(dirname "$OUTPUT")"

echo "Downloading ${URL} -> ${OUTPUT}"
curl -fL --progress-bar "$URL" -o "$OUTPUT"

echo "Verifying checksum against ${MD5_URL}"
EXPECTED_MD5=$(curl -fsSL "$MD5_URL" | awk '{print $1}')
if [ -z "$EXPECTED_MD5" ]; then
  echo "Warning: could not fetch published MD5 for ${GEOFABRIK_PATH}; skipping verification" >&2
else
  ACTUAL_MD5=$(md5 -q "$OUTPUT" 2>/dev/null || md5sum "$OUTPUT" | awk '{print $1}')
  if [ "$EXPECTED_MD5" != "$ACTUAL_MD5" ]; then
    echo "Checksum mismatch for ${GEOFABRIK_PATH}: expected ${EXPECTED_MD5}, got ${ACTUAL_MD5}" >&2
    rm -f "$OUTPUT"
    exit 1
  fi
  echo "Checksum OK: ${ACTUAL_MD5}"
fi

echo "Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
