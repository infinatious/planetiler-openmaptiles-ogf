package org.openmaptiles;

import com.onthegomap.planetiler.Planetiler;
import com.onthegomap.planetiler.config.Arguments;
import java.nio.file.Path;
import org.openmaptiles.generated.OpenMapTilesSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entrypoint for generating a map using the OpenMapTiles schema.
 */
public class OpenMapTilesMain {

  private static final Logger LOGGER = LoggerFactory.getLogger(OpenMapTilesMain.class);

  public static void main(String[] args) throws Exception {
    run(Arguments.fromArgsOrConfigFile(args));
  }

  static void run(Arguments arguments) throws Exception {
    Path dataDir = Path.of("data");
    Path sourcesDir = arguments.file("download_dir", "download directory", dataDir.resolve("sources"));
    // use --area=... argument, AREA=... env var or area=... in config to set the region of the world to use
    // will be ignored if osm_path or osm_url are set
    String area = arguments.getString(
      "area",
      "name of the extract to download if osm_url/osm_path not specified (i.e. 'monaco' 'rhode island' 'australia' or 'planet')",
      "monaco"
    );

    Planetiler.create(arguments)
      .setDefaultLanguages(OpenMapTilesSchema.LANGUAGES)
      .fetchWikidataNameTranslations(sourcesDir.resolve("wikidata_names.json"))
      // defer creation of the profile because it depends on data from the runner
      .setProfile(OpenMapTilesProfile::new)
      // OGF customization: no lake_centerline or natural_earth sources at all - both are
      // real-world-only datasets (real lakes, real countries/ice-shelves/populated places)
      // with no OpenGeoFiction equivalent. Every layer that consumes them
      // (Boundary/Landcover/Landuse/MountainPeak/Place/Transportation/Water/WaterName/Waterway)
      // degrades gracefully to "no natural earth features" when the source isn't registered -
      // for real-world-specific special cases (e.g. Transportation's Great Britain motorway
      // handling) that's the correct behavior; for generalized geometry/ranking that OGF needs
      // real content for (landcover z0-z6, water/ocean generalization, country/state ranking)
      // that's handled by dedicated OGF-specific code instead (see ogf-profile addons).
      // override any of these with arguments: --osm_path=... or --osm_url=...
      // or OSM_PATH=... OSM_URL=... environmental argument
      // or osm_path=... osm_url=... in a config file
      .addShapefileSource(OpenMapTilesProfile.WATER_POLYGON_SOURCE,
        sourcesDir.resolve("water-polygons-split-3857.zip"),
        "https://data.opengeofiction.net/extracts/water-polygons-split-3857.zip")
      .addOsmSource(OpenMapTilesProfile.OSM_SOURCE,
        sourcesDir.resolve(area.replaceAll("[^a-zA-Z]+", "_") + ".osm.pbf"),
        "https://data.opengeofiction.net/backups/ogf-planet.osm.pbf")
      // override with --output=... argument or OUTPUT=... env var or output=... in a config file
      .setOutput("pmtiles", dataDir.resolve("ogf.pmtiles"))
      .run();

    LOGGER.info("""
      Acknowledgments
      Generated vector tiles are produced work of OpenStreetMap data.
      Such tiles are reusable under CC-BY license granted by OpenMapTiles team:
      - https://github.com/openmaptiles/openmaptiles/#license
      Maps made with these vector tiles must display a visible credit:
      - © OpenMapTiles © OpenStreetMap contributors
      Thanks to all free, open source software developers and Open Data Contributors!
      """);
  }
}
