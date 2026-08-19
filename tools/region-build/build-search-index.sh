#!/usr/bin/env bash
# Builds a region's SQLite FTS4 search index via BuildSearchIndex.java.
# Usage: build-search-index.sh <osm.pbf path> <output search.db path>

set -euo pipefail

if [ $# -ne 2 ]; then
  echo "Usage: $0 <osm.pbf path> <output search.db path>" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OSM_PBF="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
OUTPUT_PATH="$2"
mkdir -p "$(dirname "$OUTPUT_PATH")"
OUTPUT_PATH="$(cd "$(dirname "$OUTPUT_PATH")" && pwd)/$(basename "$OUTPUT_PATH")"

gradle -p "$SCRIPT_DIR" buildSearchIndex -Pargs="$OSM_PBF $OUTPUT_PATH" --console=plain
