package org.openmaptiles.layers;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.onthegomap.planetiler.geo.GeoUtils;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.openmaptiles.layers.Transportation.RouteNetwork;

/**
 * Literal port of /opt/openmaptiles-customizations/ogfsql.sql's route-shield system, for
 * OpenGeoFiction (OGF) - a fictional-Earth collaborative mapping project. None of the ref
 * prefixes, region names, or bounding boxes below reflect real-world route numbering; they
 * encode this specific community's own shield/network conventions across dozens of named
 * fictional regions (Rougemont, Passitania, Ouanatchkan, Deodeca, Tempache, Michisaukee, etc).
 * <p>
 * Applied per transportation way, mirroring the SQL script's sequence of UPDATE statements
 * against {@code osm_transportation_name_network} in four stages, in the same order as the SQL:
 * <ol>
 *   <li>{@link #classifyAndRewrite}: ref-prefix + bbox rules -> {@link RouteNetwork}, plus a
 *       handful of ref rewrites interleaved with classification (ogfsql.sql lines 224-381)</li>
 *   <li>{@link #buildRoutes}: splits ';'-separated ref into up to 6 parsed route entries,
 *       each tagged with an FSA:/Deodeca: network prefix (lines 384-456, {@code parse_route_part})</li>
 *   <li>{@link #applyRegionFixups}: ~40 region-specific route-variant (BUS/TOLL/SPUR/ALT/etc.)
 *       and structural fixes (lines 523-775)</li>
 * </ol>
 * A from-scratch rebuild (this pipeline always does full rebuilds, never incremental diffs)
 * means every {@code route_N} starts empty for every way, matching the SQL's actual runtime
 * behavior even where the SQL itself guards on "IS NULL" checks that are always true here.
 */
public final class OgfRouteRules {

  private OgfRouteRules() {}

  // ===================== Stage 1: classify + interleaved ref rewrites =====================

  enum MatchMode { STARTS_WITH, CONTAINS }

  /** One classify/unclassify/rewrite step, evaluated in exact source-file order. */
  sealed interface Op {
    static Op classify(String pattern, MatchMode mode, Envelope bbox, RouteNetwork networkType) {
      return new ClassifyOp(pattern, mode, bbox, networkType);
    }

    static Op unclassify(String pattern, MatchMode mode, Envelope bbox) {
      return new UnclassifyOp(pattern, mode, bbox);
    }

    static Op rewrite(String find, String replace, MatchMode mode, String pattern, Envelope bbox,
      String requiresNetworkType, boolean requiresAnyNetwork) {
      return new RewriteOp(find, replace, mode, pattern, bbox, requiresNetworkType, requiresAnyNetwork);
    }

    /** line 370: ref like 'FS%;%TOLL%' - multi-wildcard, not expressible as STARTS_WITH/CONTAINS. */
    static Op rewriteFs370() {
      return new RewriteFs370Op();
    }

    /** lines 339-343: ref like '%GY%(XX)%' - GY somewhere, then (XX) somewhere after it. */
    static Op rewriteGyParen(String find, String replace, String code) {
      return new RewriteGyParenOp(find, replace, code);
    }
  }

  private static boolean matches(String ref, MatchMode mode, String pattern, Envelope bbox, Geometry geom) {
    boolean refMatches = mode == MatchMode.STARTS_WITH ? ref.startsWith(pattern) : ref.contains(pattern);
    if (!refMatches) {
      return false;
    }
    return bbox == null || (geom != null && bbox.intersects(geom.getEnvelopeInternal()));
  }

  private record ClassifyOp(String pattern, MatchMode mode, Envelope bbox, RouteNetwork networkType) implements Op {}

  private record UnclassifyOp(String pattern, MatchMode mode, Envelope bbox) implements Op {}

  private record RewriteOp(String find, String replace, MatchMode mode, String pattern, Envelope bbox,
    String requiresNetworkType, boolean requiresAnyNetwork) implements Op {}

  private record RewriteFs370Op() implements Op {}

  private record RewriteGyParenOp(String find, String replace, String code) implements Op {}

  // Mechanically generated from ogfsql.sql lines 224-381 by gen_ops.py - preserves EXACT
  // source order, including the interleaving between ref-rewrites (e.g. Minnonigan's
  // SR->MINN, Ouanatchkan's OA->DEO:OA) and the classification rules that read their
  // output. Do not hand-edit; regenerate instead.
  private static final List<Op> PIPELINE = List.of(
    Op.rewrite("; ", ";", MatchMode.CONTAINS, "; ", null, null, false) /* line 228 */,
    Op.classify("FS", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_INTERSTATE) /* line 231 */,
    Op.classify("TM ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 234 */,
    Op.classify("CL", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 235 */,
    Op.classify("AC ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 236 */,
    Op.classify("AC-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 237 */,
    Op.classify("S-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 238 */,
    Op.classify("AL-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 239 */,
    Op.classify("AL ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 240 */,
    Op.classify("AW-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 241 */,
    Op.classify("TN-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 242 */,
    Op.classify("RP-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 243 */,
    Op.classify("RP ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 244 */,
    Op.classify("RS ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 245 */,
    Op.classify("Z-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 246 */,
    Op.classify("WA-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 247 */,
    Op.classify("TA ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 248 */,
    Op.classify("MK-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 249 */,
    Op.classify("MK ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 250 */,
    Op.classify("WI ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 251 */,
    Op.classify("WIS ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 252 */,
    Op.classify("WI-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 253 */,
    Op.classify("WIS-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 254 */,
    Op.classify("LN-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 255 */,
    Op.classify("WM ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 256 */,
    Op.classify("M ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 257 */,
    Op.classify("GY ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 258 */,
    Op.classify("SN ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 259 */,
    Op.classify("TE ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 260 */,
    Op.classify("TE-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 261 */,
    Op.classify("CD-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 262 */,
    Op.classify("WY-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 263 */,
    Op.classify("WY ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 264 */,
    Op.classify("AQ ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 265 */,
    Op.classify("FL ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 266 */,
    Op.classify("DI ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 267 */,
    Op.classify("RT ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 268 */,
    Op.classify("SN ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 269 */,
    Op.classify("IR", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 270 */,
    Op.classify("AQ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 271 */,
    Op.classify("EU", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 272 */,
    Op.classify("OG", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 273 */,
    Op.classify("MC", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 274 */,
    Op.classify("EM ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 275 */,
    Op.classify("WH-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 276 */,
    Op.classify("PO-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 277 */,
    Op.classify("WA-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 278 */,
    Op.classify("DI-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 279 */,
    Op.classify("HY-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 280 */,
    Op.classify("HY ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 281 */,
    Op.classify("HYC-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 282 */,
    Op.classify("HYC ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 283 */,
    Op.classify("HYE-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 284 */,
    Op.classify("HYE ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 285 */,
    Op.classify("HYW-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 286 */,
    Op.classify("HYW ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 287 */,
    Op.classify("IL ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 288 */,
    Op.classify("TI-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 289 */,
    Op.classify("PM-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 290 */,
    Op.classify("PQ ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 291 */,
    Op.classify("NC ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 292 */,
    Op.classify("NC-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 293 */,
    Op.classify("OQ-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 294 */,
    Op.classify("AG ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 295 */,
    Op.classify("AS-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 296 */,
    Op.classify("GY-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 297 */,
    Op.classify("OAK-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 298 */,
    Op.classify("WS ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 299 */,
    Op.classify("OP ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 300 */,
    Op.classify("SN-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 301 */,
    Op.classify("PA-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 302 */,
    Op.classify("TJ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 303 */,
    Op.classify("EW", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 304 */,
    Op.classify("OC", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 305 */,
    Op.classify("OT-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 306 */,
    Op.classify("CN-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 307 */,
    Op.classify("RM", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 308 */,
    Op.classify("PS", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 309 */,
    Op.classify("M-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(142.935), GeoUtils.getWorldX(145.335), GeoUtils.getWorldY(-42.086), GeoUtils.getWorldY(-38.820)), RouteNetwork.US_STATE) /* line 310 */,
    Op.classify("DP", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 311 */,
    Op.classify("PA ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 312 */,
    Op.classify("AD ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 313 */,
    Op.classify("MI-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 314 */,
    Op.classify("VE-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 315 */,
    Op.classify("MG ", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 316 */,
    Op.classify("NF-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 317 */,
    Op.classify("SV-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 318 */,
    Op.rewrite("SR", "MINN", MatchMode.CONTAINS, "SR", new Envelope(GeoUtils.getWorldX(139.285), GeoUtils.getWorldX(142.218), GeoUtils.getWorldY(-42.013), GeoUtils.getWorldY(-39.011)), null, false) /* line 322 */,
    Op.classify("MINN", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(139.285), GeoUtils.getWorldX(142.218), GeoUtils.getWorldY(-42.013), GeoUtils.getWorldY(-39.011)), RouteNetwork.US_STATE) /* line 323 */,
    Op.rewrite("OA", "DEO:OA", MatchMode.CONTAINS, "OA-", new Envelope(GeoUtils.getWorldX(136.230), GeoUtils.getWorldX(139.592), GeoUtils.getWorldY(-45.931), GeoUtils.getWorldY(-42.703)), null, false) /* line 326 */,
    Op.classify("OA-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 327 */,
    Op.classify("OK-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(128.188), GeoUtils.getWorldX(163.784), GeoUtils.getWorldY(-47.577), GeoUtils.getWorldY(-25.602)), RouteNetwork.US_STATE) /* line 328 */,
    Op.classify("DEO:OA-", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(136.230), GeoUtils.getWorldX(139.592), GeoUtils.getWorldY(-45.931), GeoUtils.getWorldY(-42.703)), RouteNetwork.CA_PROVINCIAL) /* line 329 */,
    Op.rewrite("★", "", MatchMode.CONTAINS, "OC", null, "us-state", false) /* line 332 */,
    Op.rewrite("★", "", MatchMode.CONTAINS, "EW", null, "us-state", false) /* line 333 */,
    Op.rewrite("⬥", "", MatchMode.CONTAINS, "TJ", null, "us-state", false) /* line 334 */,
    Op.rewrite("•", "", MatchMode.CONTAINS, "TJ", null, "us-state", false) /* line 335 */,
    Op.rewrite("TJ", "TJ ", MatchMode.CONTAINS, "TJ", null, "us-state", false) /* line 336 */,
    Op.rewriteGyParen(" (FS)", " FS", "FS") /* line 339: ref like '%GY%(FS)%' - GY then, later, (FS) */,
    Op.rewriteGyParen(" (WM)", " WM", "WM") /* line 340: ref like '%GY%(WM)%' - GY then, later, (WM) */,
    Op.rewriteGyParen(" (PS)", " PS", "PS") /* line 341: ref like '%GY%(PS)%' - GY then, later, (PS) */,
    Op.rewriteGyParen(" (MC)", " MC", "MC") /* line 342: ref like '%GY%(MC)%' - GY then, later, (MC) */,
    Op.rewriteGyParen(" (EM)", " EM", "EM") /* line 343: ref like '%GY%(EM)%' - GY then, later, (EM) */,
    Op.classify("TDH-", MatchMode.CONTAINS, null, RouteNetwork.CA_TRANSCANADA) /* line 346 */,
    Op.classify("TDH ", MatchMode.CONTAINS, null, RouteNetwork.CA_TRANSCANADA) /* line 347 */,
    Op.classify("NW", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(137.425), GeoUtils.getWorldX(140.098), GeoUtils.getWorldY(-40.768), GeoUtils.getWorldY(-38.169)), RouteNetwork.CA_PROVINCIAL) /* line 348 */,
    Op.classify("WR", MatchMode.STARTS_WITH, new Envelope(GeoUtils.getWorldX(132.242), GeoUtils.getWorldX(137.142), GeoUtils.getWorldY(-42.066), GeoUtils.getWorldY(-38.840)), RouteNetwork.CA_PROVINCIAL) /* line 349 */,
    Op.classify("SR ", MatchMode.STARTS_WITH, null, RouteNetwork.US_STATE) /* line 353 */,
    Op.classify("FM ", MatchMode.STARTS_WITH, null, RouteNetwork.US_STATE) /* line 354 */,
    Op.classify("CR ", MatchMode.STARTS_WITH, null, RouteNetwork.US_STATE) /* line 355 */,
    Op.classify("CR-", MatchMode.STARTS_WITH, null, RouteNetwork.US_STATE) /* line 356 */,
    Op.classify("IC ", MatchMode.STARTS_WITH, null, RouteNetwork.US_STATE) /* line 357 */,
    Op.classify("I-", MatchMode.STARTS_WITH, null, RouteNetwork.US_STATE) /* line 358 */,
    Op.rewrite("Bus", "BUS", MatchMode.CONTAINS, "Bus", null, null, true) /* line 361 */,
    Op.rewrite(";BUS", " BUS", MatchMode.CONTAINS, ";BUS", null, null, true) /* line 362 */,
    Op.rewrite(";(BUS)", " BUS", MatchMode.CONTAINS, ";(BUS)", null, null, true) /* line 363 */,
    Op.rewrite("(BUS)", "BUS", MatchMode.CONTAINS, "(BUS)", null, null, true) /* line 364 */,
    Op.rewrite("Toll", "TOLL", MatchMode.CONTAINS, "Toll", null, null, true) /* line 367 */,
    Op.rewrite(";TOLL", " TOLL", MatchMode.CONTAINS, ";TOLL", null, null, true) /* line 368 */,
    Op.rewrite(";(TOLL)", " TOLL", MatchMode.CONTAINS, ";(TOLL)", null, null, true) /* line 369 */,
    Op.rewriteFs370() /* line 370: ref like 'FS%;%TOLL%' - multi-wildcard, handled specially: startsWith("FS") && contains(";") && contains("TOLL") */,
    Op.rewrite("(TOLL)", "TOLL", MatchMode.CONTAINS, "(TOLL)", null, null, true) /* line 371 */,
    Op.rewrite("Spur", "SPUR", MatchMode.CONTAINS, "Spur", null, null, true) /* line 374 */,
    Op.rewrite("(SPUR)", "SPUR", MatchMode.CONTAINS, "(SPUR)", null, null, true) /* line 375 */,
    Op.rewrite(";SPUR", " SPUR", MatchMode.CONTAINS, ";SPUR", null, null, true) /* line 376 */,
    Op.rewrite("Alt", "ALT", MatchMode.CONTAINS, "Alt", null, null, true) /* line 379 */,
    Op.rewrite("(ALT)", "ALT", MatchMode.CONTAINS, "(ALT)", null, null, true) /* line 380 */,
    Op.rewrite(";ALT", " ALT", MatchMode.CONTAINS, ";ALT", null, null, true) /* line 381 */
  );

  /** Working (ref, networkType) state threaded through stage 1. */
  private record ClassifyState(String ref, RouteNetwork networkType) {}

  // ogfsql.sql line 225: "--Date ref" - UPDATE ... SET ref = TO_CHAR(CURRENT_DATE, 'YYYY-MM-DD')
  // WHERE ref='getCurrentDate'. Some OGF feature is tagged with the literal placeholder string
  // "getCurrentDate" as its ref (and, per observation, also its name) - stamp it with the date
  // this run started (== the date the planet PBF was downloaded, since run.sh downloads then
  // immediately processes in the same invocation) rather than a real calendar date the SQL
  // would have picked up from whenever regendatabase.sh happened to run.
  private static final String TODAY = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

  static ClassifyState classifyAndRewrite(String ref, Geometry geom) {
    if (ref == null || ref.isEmpty()) {
      return new ClassifyState(ref, null);
    }
    if ("getCurrentDate".equals(ref)) {
      ref = TODAY;
    }
    String workingRef = ref;
    RouteNetwork networkType = null;
    for (Op op : PIPELINE) {
      switch (op) {
        case ClassifyOp c -> {
          if (matches(workingRef, c.mode(), c.pattern(), c.bbox(), geom)) {
            networkType = c.networkType();
          }
        }
        case UnclassifyOp u -> {
          if (matches(workingRef, u.mode(), u.pattern(), u.bbox(), geom)) {
            networkType = null;
          }
        }
        case RewriteOp r -> {
          if (r.requiresAnyNetwork() && networkType == null) {
            continue;
          }
          if (r.requiresNetworkType() != null &&
            (networkType == null || !networkType.name.equals(r.requiresNetworkType()))) {
            continue;
          }
          if (matches(workingRef, r.mode(), r.pattern(), r.bbox(), geom)) {
            workingRef = workingRef.replace(r.find(), r.replace());
          }
        }
        case RewriteFs370Op ignored -> {
          if (networkType != null && workingRef.startsWith("FS") && workingRef.contains(";") &&
            workingRef.contains("TOLL")) {
            workingRef = workingRef.replace(";", " TOLL;");
          }
        }
        case RewriteGyParenOp p -> {
          int gyIdx = workingRef.indexOf("GY");
          if (gyIdx >= 0 && workingRef.indexOf("(" + p.code() + ")", gyIdx) > gyIdx) {
            workingRef = workingRef.replace(p.find(), p.replace());
          }
        }
      }
    }
    return new ClassifyState(workingRef, networkType);
  }

  // ===================== Stage 2: route building (parse_route_part port) =====================

  private static final Pattern FIRST_SPACE_SPLIT = Pattern.compile("^(\\S+)\\s(.+)$");

  /** One parsed route entry - mirrors the hstore built by parse_route_part in the SQL. */
  public record RouteEntry(String network, String ref, String name, String colour) {}

  /**
   * ogfsql.sql's parse_route_part: normalize (trim, first hyphen -> space), split on the
   * first remaining space into (network suffix, ref), and prefix the network with FSA: for
   * us-* network types or Deodeca: for ca-* network types.
   */
  private static RouteEntry parseRoutePart(String part, RouteNetwork networkType) {
    String prefix = networkType == null ? "default:" :
      networkType.name.startsWith("us-") ? "FSA:" :
      networkType.name.startsWith("ca-") ? "Deodeca:" : "default:";
    String normalized = part.trim().replaceFirst("-", " ");
    Matcher m = FIRST_SPACE_SPLIT.matcher(normalized);
    if (!m.matches()) {
      // SQL's regexp_match returns NULL here and parts[1]/parts[2] would both be NULL;
      // treat as an unparseable part (no space to split on) - skip it, same net effect.
      return null;
    }
    return new RouteEntry(prefix + m.group(1), m.group(2), null, null);
  }

  private static final Set<String> ROUTE_BUILDING_NETWORK_TYPES = Set.of(
    "us-interstate", "us-highway", "us-state", "ca-provincial", "ca-transcanada");

  /**
   * Stage 2: splits a possibly ';'-separated ref into up to 6 RouteEntry slots (ogfsql.sql
   * lines 384-456). Only applies for the 5 network types the SQL's temp_routes WHERE clause
   * selects; other classified/unclassified ways get a single "default" route built from the
   * raw ref instead (see the "Move refs into route shields if there are no routes" stage below).
   */
  static List<RouteEntry> buildRoutes(String ref, RouteNetwork networkType) {
    List<RouteEntry> routes = new ArrayList<>();
    if (ref == null || ref.isEmpty() || networkType == null ||
      !ROUTE_BUILDING_NETWORK_TYPES.contains(networkType.name)) {
      return routes;
    }
    String[] parts = ref.split(";", -1);
    for (int i = 0; i < parts.length && i < 6; i++) {
      RouteEntry entry = parseRoutePart(parts[i], networkType);
      if (entry != null) {
        routes.add(entry);
      }
    }
    return routes;
  }

  // ===================== Stage 3: region-specific variant/structural fixups =====================

  /** (network, variant) pairs - ogfsql.sql's `SELECT apply_route_variant('NETWORK', 'VARIANT')` calls. */
  private static final List<String[]> ROUTE_VARIANTS = List.of(
    new String[] {"FSA:FS", "BUS"}, new String[] {"FSA:FS", "TOLL"}, new String[] {"FSA:FS", "SPUR"}, // Gnaerey FS
    new String[] {"FSA:TM", "SPUR"}, new String[] {"FSA:TM", "BUS"}, // Tempache
    new String[] {"FSA:MK", "ALT"}, // Makaska
    new String[] {"FSA:PQ", "ALT"}, new String[] {"FSA:PQ", "TOLL"}, // Penquisset
    new String[] {"FSA:MC", "BUS"}, new String[] {"FSA:MC", "BYP"}, new String[] {"FSA:MC", "TOLL"}, // Michisaukee
    new String[] {"FSA:IL", "BUS"}, // Illuvia
    new String[] {"FSA:HY", "ALT"}, // Hyde
    new String[] {"FSA:RM", "ALT"}, new String[] {"FSA:RM", "SPUR"}, // Rougemont
    new String[] {"FSA:PS", "TOLL"}, new String[] {"FSA:PS", "BUS"}, new String[] {"FSA:PS", "ALT"}, // Passitania
    new String[] {"FSA:MINN", "TOLL"}, // Minnonigan
    new String[] {"FSA:LN", "TOLL"}, // Laine
    new String[] {"FSA:AG", "BUS"}, // Arghenna
    new String[] {"FSA:RS", "BUS"}, new String[] {"FSA:RS", "SPUR"}, // Riopoderos
    new String[] {"FSA:WS", "SPUR"}, // Wisecota
    new String[] {"FSA:EU", "BUS"}, // Eustacia
    new String[] {"FSA:DP", "Truck"}, new String[] {"FSA:DP", "BUS"}, // Des Plaines
    new String[] {"FSA:GY", "FS"}, new String[] {"FSA:GY", "PS"}, new String[] {"FSA:GY", "MC"}, // Gnaerey
    new String[] {"FSA:GY", "WM"}, new String[] {"FSA:GY", "EM"}
  );

  /**
   * ogfsql.sql's apply_route_variant: for any route entry whose network matches exactly and
   * whose ref ends with " VARIANT", strip that suffix from ref and append ":VARIANT" to network.
   */
  private static void applyRouteVariant(List<RouteEntry> routes, String network, String variant) {
    String suffix = " " + variant;
    for (int i = 0; i < routes.size(); i++) {
      RouteEntry r = routes.get(i);
      if (network.equals(r.network()) && r.ref() != null && r.ref().endsWith(suffix)) {
        routes.set(i, new RouteEntry(network + ":" + variant,
          r.ref().substring(0, r.ref().length() - suffix.length()), r.name(), r.colour()));
      }
    }
  }

  /** ogfsql.sql's set_first_available_route: append into the first empty (< 6) slot. */
  private static void setFirstAvailableRoute(List<RouteEntry> routes, String network, String ref) {
    if (routes.size() < 6) {
      routes.add(new RouteEntry(network, ref, null, null));
    }
  }

  /**
   * Stage 3: ogfsql.sql lines 523-775 - region-specific route-variant application plus a
   * handful of one-off structural fixes. `name` is the way's OSM name tag (used by the
   * Tempache Turnpike / Des Plaines Thruway special cases), `geom` for the remaining
   * geographic-exception rules (Minnonigan SR restore, Ouanatchkan OA restore).
   */
  static void applyRegionFixups(List<RouteEntry> routes, String ref, String name, Geometry geom) {
    // Ouanatchkan (line 528-530): FSA:DEO:OA* -> DEO:OA* (drop the FSA: layer entirely)
    renameNetworkPrefix(routes, "FSA:DEO:", "DEO:", "OA");
    // Deodeca national (line 533-535): FSA:TDH* -> DEO:TDH*
    renameNetworkPrefix(routes, "FSA:", "DEO:", "TDH");

    for (String[] nv : ROUTE_VARIANTS) {
      applyRouteVariant(routes, nv[0], nv[1]);
    }

    // Tempache Turnpike (lines 546-553)
    if (name != null && name.contains("Tempache Turnpike")) {
      setFirstAvailableRoute(routes, "FSA:TM:Turnpike", null);
    }

    // Motor Trails and RTC (lines 558-578)
    for (int i = 0; i < routes.size(); i++) {
      RouteEntry r = routes.get(i);
      if ("Motor Trail".equals(r.network())) {
        routes.set(i, new RouteEntry(r.network(), "FSA:MotorTrail", r.name(), r.colour()));
      }
    }
    if ((ref != null && ref.contains("RTC")) || "TM 97".equals(ref)) {
      setFirstAvailableRoute(routes, "FSA:MotorTrail", "RTC");
    }

    // Michisaukee weird tags (lines 594-598): strip a leading double-space from FSA:MC refs
    for (int i = 0; i < routes.size(); i++) {
      RouteEntry r = routes.get(i);
      if ("FSA:MC".equals(r.network()) && r.ref() != null && r.ref().startsWith(" ")) {
        routes.set(i, new RouteEntry(r.network(), r.ref().replace("  ", ""), r.name(), r.colour()));
      }
    }

    // Des Plaines Thruway (lines 640-647)
    if (name != null && name.contains("Des Plaines Thruway")) {
      setFirstAvailableRoute(routes, "FSA:DP:Thruway", null);
    }

    // Gnaerey FS/GY prefix normalization (lines 650-681): strip a literal "FS-"/"GY-" ref
    // prefix and move it into the network name instead, for entries still tagged network=GY.
    stripRefPrefixIntoNetwork(routes, "GY", "FS-", "FSA:FS");
    stripRefPrefixIntoNetwork(routes, "GY", "GY-", "FSA:GY");

    // Gnaerey: strip stray parentheses from FSA:GY refs (lines 683-738)
    for (int i = 0; i < routes.size(); i++) {
      RouteEntry r = routes.get(i);
      if ("FSA:GY".equals(r.network()) && r.ref() != null) {
        routes.set(i, new RouteEntry(r.network(), r.ref().replaceAll("[()]", ""), r.name(), r.colour()));
      }
    }
  }

  private static void renameNetworkPrefix(List<RouteEntry> routes, String oldPrefix, String newPrefix,
    String requiredRefPrefixAfterOld) {
    for (int i = 0; i < routes.size(); i++) {
      RouteEntry r = routes.get(i);
      if (r.network() != null && r.network().startsWith(oldPrefix + requiredRefPrefixAfterOld)) {
        routes.set(i, new RouteEntry(newPrefix + r.network().substring(oldPrefix.length()), r.ref(), r.name(),
          r.colour()));
      }
    }
  }

  private static void stripRefPrefixIntoNetwork(List<RouteEntry> routes, String requiredNetwork, String refPrefix,
    String newNetwork) {
    for (int i = 0; i < routes.size(); i++) {
      RouteEntry r = routes.get(i);
      if (requiredNetwork.equals(r.network()) && r.ref() != null && r.ref().startsWith(refPrefix)) {
        routes.set(i, new RouteEntry(newNetwork, r.ref().substring(refPrefix.length()), r.name(), r.colour()));
      }
    }
  }

  // ===================== Final geographic restores / unclassify (lines 747-775) =====================

  private static final Envelope MINNONIGAN =
    new Envelope(GeoUtils.getWorldX(139.285), GeoUtils.getWorldX(142.218), GeoUtils.getWorldY(-42.013),
      GeoUtils.getWorldY(-39.011));
  private static final Envelope OUANATCHKAN =
    new Envelope(GeoUtils.getWorldX(136.230), GeoUtils.getWorldX(139.592), GeoUtils.getWorldY(-45.931),
      GeoUtils.getWorldY(-42.703));

  /** line 748: Minnonigan restore - undo the earlier MINN rewrite for the plain `ref` field. */
  static String restoreMinnonigan(String ref, Geometry geom) {
    if (ref != null && ref.contains("MINN") && geom != null && MINNONIGAN.intersects(geom.getEnvelopeInternal())) {
      return ref.replace("MINN", "SR");
    }
    return ref;
  }

  /** line 751: Ouanatchkan restore - undo the earlier DEO:OA rewrite for the plain `ref` field. */
  static String restoreOuanatchkan(String ref, Geometry geom) {
    if (ref != null && ref.contains("DEO:OA-") && geom != null &&
      OUANATCHKAN.intersects(geom.getEnvelopeInternal())) {
      return ref.replace("DEO:OA", "OA");
    }
    return ref;
  }

  /**
   * lines 772-775: a handful of prefixes that matched the broad "Secondary and county roads"
   * rule (line 353-358) get explicitly un-classified again at the very end - they're meant to
   * render as generic local roads, not state-shield routes.
   */
  static RouteNetwork finalUnclassify(String ref, RouteNetwork networkType) {
    if (ref == null) {
      return networkType;
    }
    if (ref.startsWith("SR ") || ref.startsWith("FM ") || ref.startsWith("CR-") || ref.startsWith("CR ")) {
      return null;
    }
    return networkType;
  }

  /**
   * lines 753-768: "Move refs into route shields if there are no routes" - any way that still
   * has no route entries at all (network_type was never classified, or was classified but
   * isn't one of the 5 types stage 2 builds routes for) but does have a ref gets that ref
   * split into route_1..6 with network="default" instead, so it still renders *something*.
   */
  static List<RouteEntry> buildDefaultRoutes(String ref) {
    List<RouteEntry> routes = new ArrayList<>();
    if (ref == null || ref.isEmpty()) {
      return routes;
    }
    String[] parts = ref.split(";", -1);
    for (int i = 0; i < parts.length && i < 6; i++) {
      routes.add(new RouteEntry("default", parts[i], null, null));
    }
    return routes;
  }

  // ===================== Top-level orchestrator =====================

  public record Result(String ref, RouteNetwork networkType, List<RouteEntry> routes) {}

  /**
   * Runs the full ogfsql.sql route-shield pipeline for one transportation way, in the same
   * chronological order as the source SQL script:
   *   1. classify + interleaved ref rewrites (network_type, ref)
   *   2. build route_1..6 from the (stage 1) ref, for the 5 target network types
   *   3. region-specific route_N variant/structural fixups
   *   4. Minnonigan/Ouanatchkan restore on the plain ref (route_N keeps the FSA:/DEO: form
   *      used for shield styling; the plain ref reverts to the community-facing SR/OA form)
   *   5. if still no routes but a ref exists, fall back to network="default" routes
   *   6. final SR/FM/CR- un-classification
   *
   * @param ref  the way's (or its route relations') OSM ref tag, already ';'-joined the same
   *             way TransportationName's existing relation handling does
   * @param name the way's OSM name tag (used by the Tempache Turnpike / Des Plaines Thruway
   *             special cases)
   * @param geom the way's geometry, in the same CRS as the bounding boxes above (web mercator,
   *             matching ST_Transform(..., 3857) in the source SQL)
   */
  public static Result process(String ref, String name, Geometry geom) {
    ClassifyState state = classifyAndRewrite(ref, geom);
    List<RouteEntry> routes = buildRoutes(state.ref(), state.networkType());
    applyRegionFixups(routes, state.ref(), name, geom);
    String displayRef = restoreOuanatchkan(restoreMinnonigan(state.ref(), geom), geom);
    if (routes.isEmpty() && displayRef != null && !displayRef.isEmpty()) {
      routes = buildDefaultRoutes(displayRef);
    }
    RouteNetwork finalNetworkType = finalUnclassify(displayRef, state.networkType());
    return new Result(displayRef, finalNetworkType, routes);
  }
}
