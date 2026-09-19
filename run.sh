#!/bin/bash
# OGF Planetiler Script - replaces fullrestore.sh (OpenMapTiles/imposm3/PostGIS) with
# Planetiler + PMTiles. Generation only - unlike fullrestore.sh this does not push to
# inf-10039; that's still handled by the existing OpenMapTiles pipeline for now. It does
# restart the local tileserver-gl container (see below) so it picks up a fresh build.
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
notify() {
  /opt/discord.sh --webhook-url="$WEBHOOK" --username "$(hostname) - Planetiler Production server" --text "$1"
}

cd /opt/planetiler
notify "run.sh starting at $(date)."

wget -q https://data.opengeofiction.net/backups/ogf-planet.osm.pbf -O data/sources/ogf-planet.osm.pbf
osmium merge data/sources/ogf-planet.osm.pbf data/ogf-labels.osm.pbf \
  -o data/sources/ogf-planet-with-labels.osm.pbf --overwrite
# small extract feeding Place.java's area-based country/state ranking - see OgfAdminAreaProcessor
osmium tags-filter data/sources/ogf-planet.osm.pbf boundary=administrative \
  -o data/sources/ogf-admin-boundaries.osm.pbf --overwrite
osmium export data/sources/ogf-admin-boundaries.osm.pbf --geometry-types=polygon \
  -o data/sources/ogf-admin-areas-raw.geojson --overwrite
python3 filter_admin_areas.py data/sources/ogf-admin-areas-raw.geojson data/sources/ogf-admin-areas.geojson

notify "Planet data fetched after $(($SECONDS / 3600))hrs $((($SECONDS / 60) % 60))min $(($SECONDS % 60))sec. Tile generation is now starting."

# restart the local tileserver-gl container afterward, but only for a real production run -
# it always serves data/ogf.pmtiles, so a test run with a --output= override wouldn't be
# reflected by it anyway.
RESTART_TILESERVER=true
for arg in "$@"; do
  case "$arg" in
    --output=*) RESTART_TILESERVER=false ;;
  esac
done

java -Xmx16g -jar target/*with-deps.jar --force --download --boundary_osm_only=true \
  --osm_path=data/sources/ogf-planet-with-labels.osm.pbf \
  --ogf_admin_areas_path=data/sources/ogf-admin-areas.geojson \
  --output=data/ogf.pmtiles \
  "$@"

if [ "$RESTART_TILESERVER" = true ]; then
  if docker restart ogf-tileserver-test > /dev/null; then
    notify "run.sh complete after $(($SECONDS / 3600))hrs $((($SECONDS / 60) % 60))min $(($SECONDS % 60))sec. Restarted ogf-tileserver-test."
  else
    notify "run.sh complete after $(($SECONDS / 3600))hrs $((($SECONDS / 60) % 60))min $(($SECONDS % 60))sec. WARNING: failed to restart ogf-tileserver-test."
  fi
else
  notify "run.sh complete after $(($SECONDS / 3600))hrs $((($SECONDS / 60) % 60))min $(($SECONDS % 60))sec. (custom --output, tileserver not restarted)"
fi
