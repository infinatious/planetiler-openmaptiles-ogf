#!/bin/bash
# OGF Planetiler Script - replaces fullrestore.sh (OpenMapTiles/imposm3/PostGIS) with
# Planetiler + PMTiles. Generation only - unlike fullrestore.sh this does not push to
# inf-10039; that's still handled by the existing OpenMapTiles pipeline for now.
#
# One unified world run at z0-14, unlike the old pipeline's world(z0-12) + main/newuser/
# newingerland(z13-14)-then-tile-join split: Planetiler doesn't need that trick (it was
# working around the old SQL/tilelive pipeline's inability to generate z13-14 tiles for the
# whole planet in reasonable time), so this is simpler at the cost of a larger output file.
#
# No separate QA/TST scripts - Planetiler is fast enough that a restricted test run is just
# extra arguments to this same script, passed straight through to the java command below
# and overriding the defaults (later --key=value wins). Always override --output too, or
# you'll clobber data/ogf.pmtiles. Examples:
#   ./run.sh --maxzoom=7 --output=data/ogf-test.pmtiles                    # whole world, low detail
#   ./run.sh --bounds=128.562,-45.089,163.630,-25.681 --output=data/ogf-test.pmtiles  # one region, full detail

set -o errexit
set -o pipefail

SECONDS=0
# Discord webhook comes from the environment (the GCP launcher exports it into the build VM)
# or, on the dev instance, from /etc/ogf-planetiler.env. Never hardcode it:
# this repo is public. No webhook set means no notifications.
[ -r /etc/ogf-planetiler.env ] && . /etc/ogf-planetiler.env
WEBHOOK="${DISCORD_WEBHOOK_URL:-}"
# The hostname already identifies the environment, location and service
# (prd9-ogfpt-ve01, dv32-ogfpt-ct01), so it is used as the sender name as is.
notify() {
  [ -n "$WEBHOOK" ] || return 0
  /opt/discord.sh --webhook-url="$WEBHOOK" --username "$(hostname)" --text "$1" || true
}

cd /opt/planetiler
mkdir -p data/sources

wget -q https://data.opengeofiction.net/backups/ogf-planet.osm.pbf -O data/sources/ogf-planet.osm.pbf
osmium merge data/sources/ogf-planet.osm.pbf data/ogf-labels.osm.pbf \
  -o data/sources/ogf-planet-with-labels.osm.pbf --overwrite
# small extract feeding Place.java's area-based country/state ranking - see OgfAdminAreaProcessor
osmium tags-filter data/sources/ogf-planet.osm.pbf boundary=administrative \
  -o data/sources/ogf-admin-boundaries.osm.pbf --overwrite
osmium export data/sources/ogf-admin-boundaries.osm.pbf --geometry-types=polygon \
  -o data/sources/ogf-admin-areas-raw.geojson --overwrite
python3 filter_admin_areas.py data/sources/ogf-admin-areas-raw.geojson data/sources/ogf-admin-areas.geojson

# Size the heap off whatever RAM is actually on this machine (was a static
# -Xmx16g, seemingly just sized for inf-11008's 24GB and never revisited
# after moving to 62-64GB cloud build VMs) - 80% leaves headroom for the
# OS/JVM off-heap overhead without needing to be re-tuned every time the
# machine type changes.
TOTAL_MEM_GB=$(free -g | awk '/^Mem:/{print $2}')
HEAP_GB=$((TOTAL_MEM_GB * 80 / 100))
java -Xmx${HEAP_GB}g -jar target/*with-deps.jar --force --download --boundary_osm_only=true \
  --osm_path=data/sources/ogf-planet-with-labels.osm.pbf \
  --ogf_admin_areas_path=data/sources/ogf-admin-areas.geojson \
  --output=data/ogf.pmtiles \
  "$@"

notify "run.sh complete after $(($SECONDS / 3600))hrs $((($SECONDS / 60) % 60))min $(($SECONDS % 60))sec."
