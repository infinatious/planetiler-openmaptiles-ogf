# OGF Vector Tile Pipeline

This is [OpenGeoFiction](https://opengeofiction.net)'s fork of [`planetiler-openmaptiles`](https://github.com/openmaptiles/planetiler-openmaptiles), a Java port of [OpenMapTiles](https://github.com/openmaptiles/openmaptiles) for [Planetiler](https://github.com/onthegomap/planetiler). It replaced OGF's old OpenMapTiles/imposm3/PostGIS pipeline in September 2026 — a full-world build now takes minutes instead of days, since Planetiler reads OSM PBF directly with no database step.

OGF is a fictional-Earth collaborative mapping project, not real-world OpenStreetMap — so on top of the upstream port, this fork:

- **Removes all Natural Earth dependencies** (no real-world basemap data makes sense for a fictional world) — land/water polygon sources point at `data.opengeofiction.net` instead, and `Water.java`/`Landcover.java` generalize low-zoom water/land directly from those rather than a separate real-world dataset.
- **Ports OGF's route-shield system** (`OgfRouteRules.java`) — a literal 1:1 translation of the old pipeline's ~500-line `ogfsql.sql` ref-prefix + bounding-box rules (Rougemont, Ouanatchkan, Deodeca, and every other named OGF region) into Java, wired into `TransportationName.java`.
- **Computes real country/state label ranking** (`Place.java`) from actual `admin_level=2`/`4` boundary area, via a small OSM pre-processing pass (`filter_admin_areas.py`), instead of a flat hardcoded value.
- **Injects OGF's continent/ocean label points** (`data/ogf-labels.osm.pbf`) that Natural Earth would otherwise have supplied.

See **OGF customizations** below for details on each of these. This repo is just the code — for the full production architecture it feeds into (scheduled builds, Cloud Run serving, DNS, etc.), see the `OGF Vector Pipeline (Planetiler + TileServerGL + Americana).md` operations doc.

## How to run

```bash
./mvnw clean package -DskipTests   # NOT `compile` — see gotcha below
./run.sh                            # full production world run, z0-14 → data/ogf.pmtiles
```

**Build gotcha**: after editing any `.java` file, always rebuild with `clean package` (or at least plain `package`), never `./mvnw compile` — `compile` only refreshes `target/classes/*.class`, not the shaded `target/*-with-deps.jar` that `run.sh` actually runs. A fix that "does nothing" is usually just this.

**Test runs** — no separate QA/TST scripts; `run.sh` forwards extra args straight to the `java` command, and later `--key=value` args override the defaults. Always pass `--output=` on a test run or it'll overwrite `data/ogf.pmtiles`:

```bash
./run.sh --maxzoom=7 --output=data/ogf-test.pmtiles                                       # whole world, low detail, fast
./run.sh --bounds=128.562,-45.089,163.630,-25.681 --output=data/ogf-test.pmtiles          # one region, full detail
```

The upstream base project also supports running via pre-built Docker image or plain `--area=` extracts — see [Planetiler README.md](https://github.com/onthegomap/planetiler/blob/main/README.md) for those generic options, though OGF's own builds always go through `run.sh` above.

## OGF customizations

### Adding/updating route-shield rules

`OgfRouteRules.java` is organized by region name (matching `ogfsql.sql`'s old comment headers — Rougemont, Ouanatchkan, Deodeca, etc.):

1. Ref-prefix classification/rewrite logic goes in `PIPELINE` (**order matters** — later rules can depend on earlier rewrites); structural/region-specific fixups go in `applyRegionFixups()`/`ROUTE_VARIANTS`.
2. **Always wrap raw lon/lat through `GeoUtils.getWorldX()`/`getWorldY()`** when building an `Envelope` — Planetiler geometry is normalized `[0,1]×[0,1]` world space, not degrees. Passing raw degrees compiles fine and just silently never matches — the single most likely way a new rule quietly fails.
3. Rebuild (`clean package`, see gotcha above), then run `OgfRouteRulesSmokeTest.java`'s standalone `main()` (not JUnit) — add a case for your new rule, or just run the existing ones as a sanity check.
4. Validate against real data with a bounded test run, then decode a tile and check the way's `network`/`route_1_network`/`route_1_ref` attributes:
   ```bash
   ~/go/bin/go-pmtiles tile data/ogf-test.pmtiles Z X Y | gunzip -c > tile.raw
   python3 -c "import mapbox_vector_tile; print(mapbox_vector_tile.decode(open('tile.raw','rb').read())['transportation'])"
   ```

### Country/state label ranking

Country/state point `rank` (1-6, driving label priority/min-zoom) comes from real boundary area, not a flat constant:

1. `run.sh` runs `osmium tags-filter ... boundary=administrative` → `osmium export --geometry-types=polygon` → `filter_admin_areas.py` (keeps `admin_level∈{2,4}`) → `data/sources/ogf-admin-areas.geojson`, registered as a second Planetiler source (`ogf_admin_areas`).
2. `Place.processOgfAdminArea()` indexes each polygon's area into `COUNTRY_AREA_RANKS` (a separate scale from the existing island/protected-area `AREA_RANKS`, which tops out too low to distinguish countries). Points with no matching boundary default to rank 3.
3. To retune, adjust `COUNTRY_AREA_RANKS` thresholds or `areaRankMinZoom()` directly in `Place.java`.

### Low-zoom landcover

`Landcover.postProcess()` merges same-subclass patches at z≤6 via `FeatureMerge.mergeNearbyPolygons` (not `mergeOverlappingPolygons`, so *nearby*-but-not-touching patches still merge), with `minDist`/`buffer` scaled by zoom via the `LOW_ZOOM_MERGE_DISTANCE` `TreeMap`. If low-zoom landcover looks patchy or over-merged after a data change, that map's values are the first thing to adjust.

## Differences from OpenMapTiles

- Road name abbreviations are not implemented yet in the `transportation_name` layer
- `brunnel` tag is excluded from `transportation_name` layer to avoid breaking apart long `transportation_name`
  lines, to revert this behavior set `--transportation-name-brunnel=true`
- `rank` field on `mountain_peak` linestrings only has 3 levels (1: has wikipedia page and name, 2: has name, 3: no name
  or wikipedia page or name)
- Some line and polygon tolerances are different, can be tweaked with `--simplify-tolerance` parameter
- For bigger bays whose label points show above Z9, centerline is used for Z9+
- MVT IDs encoded as `{ID} * 10 + {1 for OSM nodes, 2 for OSM ways, 3 for OSM relations, 0 for any other source}` by default

## Customizing

If you want to exclude layers or only include certain layers, then run the project
with  `--exclude-layers=poi,housenumber,...` or `--only-layers=water,transportation,...` command-line arguments.

If you want to customize existing layers in OpenMapTiles, then fork this repo, find the appropriate class from
the [layers package](src/main/java/org/openmaptiles/layers), and make a change to where it processes output features.

<details>
<summary>
Example adding an attribute to a built-in layer
</summary>

For example to copy over the name attribute from OpenStreetMap elements to the building layer,
modify [Building.java](src/main/java/org/openmaptiles/layers/Building.java):

```diff
@@ -166,6 +166,7 @@ public class Building implements
         .setAttrWithMinzoom(Fields.RENDER_MIN_HEIGHT, renderMinHeight, 14)
         .setAttrWithMinzoom(Fields.COLOUR, color, 14)
         .setAttrWithMinzoom(Fields.HIDE_3D, hide3d, 14)
+        .setAttrWithMinzoom("name", element.source().getTag("name"), 14)
         .setSortKey(renderHeight);
       if (mergeZ13Buildings) {
         feature
```

</details>

If you want to generate a mbtiles file with OpenMapTiles base layers plus some extra ones then fork this repo and:

1. Create a new class that implements the [`Layer` interface](src/main/java/org/openmaptiles/Layer.java) in
   the [addons package](src/main/java/org/openmaptiles/addons) and make the `public String name()` method return the ID
   of the new layer.
2. Make the new class implement interfaces from `OpenMapTilesProfile` to register handlers for elements from input
   sources. For example implement `OpenMapTilesProfile.OsmAllProcessor` to handle every OSM element from `processAllOsm`
   method. See the [built-in layers](src/main/java/org/openmaptiles/layers) for examples.
3. Create a new instance of that class from the [`ExtraLayers`](src/main/java/org/openmaptiles/addons/ExtraLayers.java)
   class.

<details>
<summary>
Custom layer example
</summary>

This layer would add a `power` layer to OpenMapTiles output with power lines:

```java
package org.openmaptiles.addons;

import com.onthegomap.planetiler.FeatureCollector;
import com.onthegomap.planetiler.reader.SourceFeature;
import org.openmaptiles.Layer;
import org.openmaptiles.OpenMapTilesProfile;

public class Power implements Layer, OpenMapTilesProfile.OsmAllProcessor {

  private static final String LAYER_NAME = "power";

  @Override
  public String name() {
    return LAYER_NAME;
  }

  @Override
  public void processAllOsm(SourceFeature feature, FeatureCollector features) {
    if (feature.canBeLine() && feature.hasTag("power", "line")) {
      features.line("power")
          .setBufferPixels(4)
          .setMinZoom(6)
          .setAttr("class", "line");
    }
  }
}
```

</details>

If you think your custom layer or change to a built-in layer might be useful to others, consider opening a pull request
to contribute it back to this repo. Any change that diverges from what is produced
by https://github.com/openmaptiles/openmaptiles should be disabled by default, and enabled through a command-line
argument that users can opt-into. For example, see how
the [building layer](src/main/java/org/openmaptiles/layers/Building.java) exposes a `building_merge_z13` command-line
argument to disable merging nearby buildings at z13.

## Code Layout

[Generate.java](src/main/java/org/openmaptiles/Generate.java) generates code in
the [generated](src/main/java/org/openmaptiles/generated) package from an OpenMapTiles tag in
GitHub:

- [OpenMapTilesSchema](src/main/java/org/openmaptiles/generated/OpenMapTilesSchema.java)
  contains an interface for each layer with constants for the name, attributes, and allowed values for each tag in that
  layer
- [Tables](src/main/java/org/openmaptiles/generated/Tables.java)
  contains a record for each table that OpenMapTiles [imposm3](https://github.com/omniscale/imposm3) configuration
  generates (along with the tag-filtering expression) so layers can listen on instances of those records instead of
  doing the tag filtering and parsing themselves

The [layers](src/main/java/org/openmaptiles/layers) package contains a port of the SQL logic to
generate each layer from OpenMapTiles. Layers define how source features (or parsed imposm3 table rows) map to vector
tile features, and logic for post-processing tile geometries.

[OpenMapTilesProfile](src/main/java/org/openmaptiles/OpenMapTilesProfile.java) dispatches source
features to layer handlers and merges the results.

[OpenMapTilesMain](src/main/java/org/openmaptiles/OpenMapTilesMain.java) is the main driver that
registers source data and output location — this is where OGF's source-config overrides live (Natural Earth removed, `water_polygons`/OSM source repointed at `data.opengeofiction.net`, `ogf_admin_areas` GeoJSON source registered, output format `pmtiles`).

OGF-specific additions on top of the upstream layout above:

- [`OgfRouteRules.java`](src/main/java/org/openmaptiles/layers/OgfRouteRules.java) — the route-shield port (see **OGF customizations** above)
- [`OgfRouteRulesSmokeTest.java`](src/main/java/org/openmaptiles/layers/OgfRouteRulesSmokeTest.java) — standalone `main()` smoke test for the above, not JUnit
- [`filter_admin_areas.py`](filter_admin_areas.py) — filters raw `osmium export` GeoJSON down to `admin_level∈{2,4}` polygons for country/state ranking
- [`run.sh`](run.sh) — the only run script; handles planet data download, the Planetiler build itself, and Discord notifications
- [`data/ogf-labels.osm.pbf`](data/ogf-labels.osm.pbf) — 9 continent + 5 ocean label points, merged into the OSM input before every run
- `Water.java`, `Landcover.java`, `Place.java`, `TransportationName.java` (all in [layers](src/main/java/org/openmaptiles/layers)) — modified from upstream, see **OGF customizations** above

## Regenerating Code

**Note for this fork**: this regenerates the upstream schema/tables code only (`generated` package) — it doesn't touch or affect OGF's own customizations (`OgfRouteRules.java`, `OpenMapTilesMain.java`'s source overrides, etc.), which live in separate files untouched by this script. Safe to run when picking up a newer OpenMapTiles schema version.

To run `Generate.java`,
use [scripts/regenerate-openmaptiles.sh](https://github.com/openmaptiles/planetiler-openmaptiles/blob/main/scripts/regenerate-openmaptiles.sh)
script with the
OpenMapTiles release tag:

```bash
./scripts/regenerate-openmaptiles.sh v3.16
```

Then follow the instructions it prints for reformatting generated code.

If you want to regenerate from a different repository than the default openmaptiles, you can specify the url like this:

```bash
./scripts/regenerate-openmaptiles.sh v3.16 https://raw.githubusercontent.com/openmaptiles/openmaptiles/
```

## License

All code in this repository is under the [BSD license](./LICENSE.md) and the cartography decisions encoded in the schema
and SQL are licensed under [CC-BY](./LICENSE.md).

Products or services using maps derived from OpenMapTiles schema need to **visibly credit "OpenMapTiles.org"** or
**reference "OpenMapTiles"** with a link to https://openmaptiles.org/. Exceptions to attribution requirement can be granted
on request.

For a browsable electronic map based on OpenMapTiles and OpenStreetMap data, the
credit should appear in the corner of the map. For example:

[© OpenMapTiles](https://openmaptiles.org/) [© OpenStreetMap contributors](https://www.openstreetmap.org/copyright)

For printed and static maps a similar attribution should be made in a textual
description near the image, in the same fashion as if you cite a photograph.
