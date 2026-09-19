#!/usr/bin/env python3
"""Filter an osmium-export GeoJSON down to boundary=administrative admin_level=2/4 polygons
only, for Place.java's OgfAdminAreaProcessor (area-based country/state ranking).
osmium export also emits every other tagged closed way it finds (coastlines, etc.) since it
doesn't know which ways were kept only as referenced relation members - this drops those.
Usage: filter_admin_areas.py <in.geojson> <out.geojson>
"""
import json
import sys

with open(sys.argv[1]) as f:
    data = json.load(f)

out = [
    feat for feat in data["features"]
    if feat["properties"].get("boundary") == "administrative"
    and feat["properties"].get("admin_level") in ("2", "4")
]

with open(sys.argv[2], "w") as f:
    json.dump({"type": "FeatureCollection", "features": out}, f)

print(f"kept {len(out)} of {len(data['features'])} features", file=sys.stderr)
