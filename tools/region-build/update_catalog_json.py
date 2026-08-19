#!/usr/bin/env python3
"""Updates (or creates) regions.json with a real, freshly-computed entry for one region - called
by upload-release.sh after the archive is actually uploaded, so downloadSizeBytes/sha256 always
reflect the real uploaded file, never a guess."""
import argparse
import csv
import hashlib
import json
import os
import sys


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
    parser.add_argument("--catalog-csv", required=True)
    parser.add_argument("--archive", required=True)
    parser.add_argument("--region-id", required=True)
    parser.add_argument("--regions-json", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--repo", required=True)
    args = parser.parse_args()

    region = load_region(args.catalog_csv, args.region_id)

    if os.path.isfile(args.regions_json):
        with open(args.regions_json) as f:
            catalog = json.load(f)
    else:
        catalog = {"schemaVersion": 1, "regions": []}

    entry = {
        "id": region["id"],
        "displayName": region["displayName"],
        "countryCode": "DE",
        "bboxSouth": float(region["boundsSouth"]),
        "bboxWest": float(region["boundsWest"]),
        "bboxNorth": float(region["boundsNorth"]),
        "bboxEast": float(region["boundsEast"]),
        "packageUrl": f"https://github.com/{args.repo}/releases/download/{args.release_tag}/{region['id']}.rpregion",
        "downloadSizeBytes": os.path.getsize(args.archive),
        "sha256": sha256_of(args.archive),
        "formatVersion": 1,
    }

    regions = [r for r in catalog.get("regions", []) if r.get("id") != region["id"]]
    regions.append(entry)
    regions.sort(key=lambda r: r["id"])
    catalog["regions"] = regions

    with open(args.regions_json, "w") as f:
        json.dump(catalog, f, indent=2, sort_keys=True)
        f.write("\n")

    print(f"regions.json now has {len(regions)} region(s); updated entry for {region['id']}")


if __name__ == "__main__":
    sys.exit(main())
