#!/usr/bin/env python3
"""Packages one region's built tiles/graph/search-index into a <id>.rpregion tar.gz archive,
matching the layout RegionDownloadManager.kt extracts on-device: manifest.json (per-file sha256)
+ tiles.mbtiles + graphhopper/ + search.db. Region displayName/bounds come from
regions-catalog.csv, the same real, Geofabrik-sourced data the app's built-in fallback catalog
and regions.json are seeded from - never invented here.
"""
import argparse
import csv
import hashlib
import json
import os
import shutil
import sys
import tarfile
import tempfile


def sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_region(catalog_csv, region_id):
    with open(catalog_csv, newline="") as f:
        for row in csv.DictReader(f):
            if row["id"] == region_id:
                return row
    raise SystemExit(f"Region '{region_id}' not found in {catalog_csv}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--id", required=True)
    parser.add_argument("--tiles", required=True)
    parser.add_argument("--graphhopper-dir", required=True)
    parser.add_argument("--search-db", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    region = load_region(args.catalog, args.id)

    staging = tempfile.mkdtemp(prefix=f"rpregion-{args.id}-")
    try:
        gh_dest = os.path.join(staging, "graphhopper")
        os.makedirs(gh_dest)
        shutil.copy2(args.tiles, os.path.join(staging, "tiles.mbtiles"))
        shutil.copy2(args.search_db, os.path.join(staging, "search.db"))
        for name in sorted(os.listdir(args.graphhopper_dir)):
            src = os.path.join(args.graphhopper_dir, name)
            if os.path.isfile(src):
                shutil.copy2(src, os.path.join(gh_dest, name))

        files = {}
        total_size = 0
        for root, _dirs, names in os.walk(staging):
            for name in names:
                path = os.path.join(root, name)
                rel = os.path.relpath(path, staging)
                size = os.path.getsize(path)
                total_size += size
                files[rel] = {"sizeBytes": size, "sha256": sha256_of(path)}

        manifest = {
            "id": region["id"],
            "displayName": region["displayName"],
            "formatVersion": 1,
            "boundsSouth": float(region["boundsSouth"]),
            "boundsWest": float(region["boundsWest"]),
            "boundsNorth": float(region["boundsNorth"]),
            "boundsEast": float(region["boundsEast"]),
            "installedSizeBytes": total_size,
            "files": files,
        }
        manifest_path = os.path.join(staging, "manifest.json")
        with open(manifest_path, "w") as f:
            json.dump(manifest, f, indent=2, sort_keys=True)
            f.write("\n")

        os.makedirs(os.path.dirname(os.path.abspath(args.output)) or ".", exist_ok=True)
        with tarfile.open(args.output, "w:gz") as tar:
            for name in sorted(os.listdir(staging)):
                tar.add(os.path.join(staging, name), arcname=name)

        archive_sha256 = sha256_of(args.output)
        archive_size = os.path.getsize(args.output)
        print(f"Packaged {args.output}")
        print(f"  installedSizeBytes: {total_size}")
        print(f"  downloadSizeBytes:  {archive_size}")
        print(f"  sha256:             {archive_sha256}")
        print()
        print("regions.json entry (preview - the real write happens in update_catalog_json.py):")
        print(json.dumps({
            "id": region["id"],
            "displayName": region["displayName"],
            "continent": region["continent"],
            "country": region["country"],
            "bboxSouth": float(region["boundsSouth"]),
            "bboxWest": float(region["boundsWest"]),
            "bboxNorth": float(region["boundsNorth"]),
            "bboxEast": float(region["boundsEast"]),
            "packageUrl": f"https://github.com/bhavyarupani/roadpulse/releases/download/regions-v1/{region['id']}.rpregion",
            "downloadSizeBytes": archive_size,
            "sha256": archive_sha256,
            "formatVersion": 1,
        }, indent=2))
    finally:
        shutil.rmtree(staging, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
