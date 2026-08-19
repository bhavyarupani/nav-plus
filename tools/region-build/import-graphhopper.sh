#!/usr/bin/env bash
# Imports a region's .osm.pbf into a GraphHopper routing graph via GraphHopperImporter.java.
# Usage: import-graphhopper.sh <osm.pbf path> <output graph dir>

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <osm.pbf path> <output graph dir>" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OSM_PBF="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUTPUT_DIR="$2"
mkdir -p "$OUTPUT_DIR"
OUTPUT_DIR="$(cd "$OUTPUT_DIR" && pwd)"

gradle -p "$SCRIPT_DIR" importGraphHopper -Pargs="$OSM_PBF $OUTPUT_DIR" --console=plain
