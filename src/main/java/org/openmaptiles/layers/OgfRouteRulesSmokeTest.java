package org.openmaptiles.layers;

import com.onthegomap.planetiler.geo.GeoUtils;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/**
 * Standalone smoke test (not JUnit - just a main() to run and eyeball against ogfsql.sql).
 * IMPORTANT: test geometries must be built in Planetiler's world coordinate space (via
 * GeoUtils.getWorldX/getWorldY), matching what element.source().worldGeometry() actually
 * returns in production - NOT raw lon/lat degrees. An earlier version of this test used raw
 * lon/lat directly, which matched an equivalent bug in OgfRouteRules' bbox construction at the
 * time, so it passed despite being wrong for real production geometries - a same-bug-on-both-
 * sides false negative, not a real validation.
 */
public class OgfRouteRulesSmokeTest {
  static final GeometryFactory GF = new GeometryFactory();

  static LineString pointAt(double lon, double lat) {
    double x = GeoUtils.getWorldX(lon);
    double y = GeoUtils.getWorldY(lat);
    return GF.createLineString(new Coordinate[] {new Coordinate(x, y), new Coordinate(x + 0.0001, y)});
  }

  static void check(String label, String ref, double lon, double lat, String expectedNetworkType,
    String expectedRoute1Network, String expectedRoute1Ref) {
    var geom = pointAt(lon, lat);
    var result = OgfRouteRules.process(ref, null, geom);
    String actualNetworkType = result.networkType() == null ? "null" : result.networkType().name;
    String actualRoute1Network = result.routes().isEmpty() ? "null" : result.routes().get(0).network();
    String actualRoute1Ref = result.routes().isEmpty() ? "null" : result.routes().get(0).ref();
    boolean ok = actualNetworkType.equals(expectedNetworkType) &&
      actualRoute1Network.equals(expectedRoute1Network == null ? "null" : expectedRoute1Network) &&
      actualRoute1Ref.equals(expectedRoute1Ref == null ? "null" : expectedRoute1Ref);
    System.out.printf("[%s] %s: ref=%-20s -> displayRef=%-20s networkType=%-15s route1=(%s, %s)%n",
      ok ? "PASS" : "FAIL", label, ref, result.ref(), actualNetworkType, actualRoute1Network, actualRoute1Ref);
    if (!ok) {
      System.out.printf("       expected: networkType=%s route1=(%s, %s)%n", expectedNetworkType,
        expectedRoute1Network, expectedRoute1Ref);
    }
  }

  public static void main(String[] args) {
    // Rougemont: ref like 'RM%' -> us-state (line 308), bbox OGF_MAIN
    check("Rougemont plain", "RM 5", 145.0, -35.0, "us-state", "FSA:RM", "5");

    // Rougemont ALT variant (line 607): FSA:RM:ALT should apply, "ALT" suffix stripped from ref
    check("Rougemont ALT", "RM 5 ALT", 145.0, -35.0, "us-state", "FSA:RM:ALT", "5");

    // FS interstate (line 231)
    check("FS interstate", "FS 10", 145.0, -35.0, "us-interstate", "FSA:FS", "10");

    // FS BUS variant (line 538)
    check("FS BUS variant", "FS 10 BUS", 145.0, -35.0, "us-interstate", "FSA:FS:BUS", "10");

    // Minnonigan: SR within Minnonigan bbox -> rewritten to MINN, classified us-state via route
    // building, but restored back to "SR" for display and then FINAL-unclassified (matches the
    // "Fix secondary routes which should be squares" comment - route_1 still has FSA:MINN data,
    // top-level networkType ends up null because restored ref starts with "SR ").
    check("Minnonigan SR", "SR 10", 140.5, -40.5, "null", "FSA:MINN", "10");

    // Plain 'SR ' prefix outside any special bbox -> classified us-state (line 353) then
    // final-unclassified (line 772) the same way, route_1 still built with FSA: prefix via the
    // generic (non-Minnonigan) path... actually SR alone never got a bbox-scoped classify rule
    // except via the global "Secondary and county roads" rule, so let's check that specifically.
    check("Generic SR (no bbox rule)", "SR 20", 0.0, 0.0, "null", "FSA:SR", "20");

    // Ouanatchkan: OA- within Ouanatchkan bbox -> rewritten to DEO:OA-, classified ca-provincial,
    // restored back to OA- for display. parse_route_part gives ca-* types "Deodeca:" (the
    // rename at line 528-530 targets "FSA:DEO:OA%", which nothing produces via this path since
    // ca-provincial always gets "Deodeca:" not "FSA:" - faithfully ported as written regardless).
    check("Ouanatchkan OA", "OA-5", 137.5, -44.0, "ca-provincial", "Deodeca:DEO:OA", "5");

    // Deodeca national TDH (line 346, no bbox) -> ca-transcanada. parse_route_part gives ca-*
    // network types the "Deodeca:" prefix (not "FSA:"), so the line 533-535 "FSA:TDH -> DEO:TDH"
    // rename never actually matches this (it targets network='FSA:TDH%', which nothing produces
    // once TDH always classifies as ca-transcanada) - faithfully ported as written regardless.
    check("Deodeca TDH", "TDH-1", 0.0, 0.0, "ca-transcanada", "Deodeca:TDH", "1");

    // Plain local street, no route relation at all -> should NOT be OGF-classified
    check("Unclassified local street", null, 0.0, 0.0, "null", "null", "null");
  }
}
