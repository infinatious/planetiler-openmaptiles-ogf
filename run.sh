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
WEBHOOK="https://discord.com/api/webhooks/1512547109805625364/mae_tCiygS4RbXGT1XGMhksxhZsjQ_9zmiQuECRaX7YcsAkDFW4_-EiYOjtgpELWk4_-"
# PLATFORM is unset on inf-11008 (hostname alone is unambiguous there); the
# cloud build scripts export PLATFORM=GCP/AWS before calling this so Discord
# messages from any run consistently read "<hostname> (<platform>)". Cloud
# build hostnames already say "planetiler-build-..." so that's enough on its
# own; inf-11008's hostname doesn't mention Planetiler at all, so add the
# suffix only when the hostname itself doesn't already say it.
notify() {
  local host label
  host="$(hostname)"
  label="$host"
  [[ "${host,,}" == *planetiler* ]] || label="$host - Planetiler"
  /opt/discord.sh --webhook-url="$WEBHOOK" --username "${label}${PLATFORM:+ ($PLATFORM)}" --text "$1"
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

java -Xmx16g -jar target/*with-deps.jar --force --download --boundary_osm_only=true \
  --osm_path=data/sources/ogf-planet-with-labels.osm.pbf \
  --ogf_admin_areas_path=data/sources/ogf-admin-areas.geojson \
  --output=data/ogf.pmtiles \
  "$@"

notify "run.sh complete after $(($SECONDS / 3600))hrs $((($SECONDS / 60) % 60))min $(($SECONDS % 60))sec."
