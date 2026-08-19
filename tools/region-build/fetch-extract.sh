#!/usr/bin/env bash
# Downloads a Geofabrik .osm.pbf extract for one region, verified against Geofabrik's own
# published MD5 sum - the same raw-data source already used for the Bremen extract (see
# ZERO_COST_ARCHITECTURE.md). Geofabrik hosts these for free, unlimited, as their own public
# service - this is the one network dependency in the whole region-build pipeline that isn't
# our own infrastructure.
#
# Usage: fetch-extract.sh <geofabrik-slug> <output.osm.pbf>
# Example: fetch-extract.sh baden-wuerttemberg tools/data/de-bw.osm.pbf

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <geofabrik-slug> <output.osm.pbf>" >&2
  exit 1
fi

SLUG="$1"
OUTPUT="$2"
URL="https://download.geofabrik.de/europe/germany/${SLUG}-latest.osm.pbf"
MD5_URL="${URL}.md5"

mkdir -p "$(dirname "$OUTPUT")"

echo "Downloading ${URL} -> ${OUTPUT}"
curl -fL --progress-bar "$URL" -o "$OUTPUT"

echo "Verifying checksum against ${MD5_URL}"
EXPECTED_MD5=$(curl -fsSL "$MD5_URL" | awk '{print $1}')
if [ -z "$EXPECTED_MD5" ]; then
  echo "Warning: could not fetch published MD5 for ${SLUG}; skipping verification" >&2
else
  ACTUAL_MD5=$(md5 -q "$OUTPUT" 2>/dev/null || md5sum "$OUTPUT" | awk '{print $1}')
  if [ "$EXPECTED_MD5" != "$ACTUAL_MD5" ]; then
    echo "Checksum mismatch for ${SLUG}: expected ${EXPECTED_MD5}, got ${ACTUAL_MD5}" >&2
    rm -f "$OUTPUT"
    exit 1
  fi
  echo "Checksum OK: ${ACTUAL_MD5}"
fi

echo "Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
