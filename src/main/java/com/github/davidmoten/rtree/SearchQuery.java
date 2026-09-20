package com.github.davidmoten.rtree;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.rtree.geometry.Circle;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Intersects;
import com.github.davidmoten.rtree.geometry.Line;
import com.github.davidmoten.rtree.geometry.Point;
import com.github.davidmoten.rtree.geometry.Rectangle;

import rx.functions.Func1;
import rx.functions.Func2;

/**
 * Identifies a spatial query independently of the tree instance it is run
 * against. A query is the combination of:
 *
 * <ul>
 * <li>a stable {@link #id()} used inside resume tokens,</li>
 * <li>an ordered, string valued {@link #parameters()} map that fully
 * describes the geometry predicate (empty for a custom predicate),</li>
 * <li>a {@code nodeCondition} predicate applied to internal node bounding
 * rectangles and to leaf entry geometries during depth-first traversal, and</li>
 * <li>an optional {@code entryFilter} applied after traversal to refine
 * matches on the actual entry geometry.</li>
 * </ul>
 *
 * <p>
 * The built-in factories ({@link #intersects(Rectangle)}, {@link #within} and
 * friends) produce queries whose predicates are fully described by their
 * parameters, so tokens issued in one process are valid in another. A query
 * created with {@link #custom(String, Func1)} can only be resumed with the
 * same query instance (identified by the supplied id); the predicate closure
 * itself is never serialized.
 */
public abstract class SearchQuery<T, S extends Geometry> {

    /** Token id for rectangle and point intersection queries. */
    public static final String ID_INTERSECTS_RECTANGLE = "intersects.rectangle";
    /** Token id for circle intersection queries. */
    public static final String ID_INTERSECTS_CIRCLE = "intersects.circle";
    /** Token id for line intersection queries. */
    public static final String ID_INTERSECTS_LINE = "intersects.line";
    /** Token id for within-distance-of-rectangle queries. */
    public static final String ID_WITHIN_DISTANCE_RECTANGLE = "withinDistance.rectangle";
    /** Token id for entries-only (always true predicate) queries. */
    public static final String ID_ALL = "all";

    SearchQuery() {
        // limit subclassing to this package
    }

    /**
     * Stable identifier of the query kind.
     *
     * @return query id
     */
    public abstract String id();

    /**
     * Ordered string parameters fully describing the predicate. The map is
     * order independent for equality.
     *
     * @return immutable parameter map
     */
    public abstract Map<String, String> parameters();

    /**
     * Predicate applied to internal node minimum bounding rectangles and to
     * leaf entry geometries. Must be <em>conservative</em>: if it returns
     * false for a node MBR, no descendant entry can match.
     *
     * @return node and leaf geometry predicate
     */
    public abstract Func1<? super Geometry, Boolean> nodeCondition();

    /**
     * Optional refinement applied to matched entries on their actual
     * geometry. Returns {@code null} when no refinement is required.
     *
     * @return entry predicate or {@code null}
     */
    public abstract Func1<? super Entry<T, S>, Boolean> entryFilter();

    /**
     * Returns an intersection query with the given rectangle. Point
     * intersection uses the same query kind with the point's bounding
     * rectangle, so {@link RTree#search(Point)} and
     * {@link RTree#search(Rectangle)} share symmetric entry points.
     *
     * @param r
     *            rectangle to intersect
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return query
     */
    public static <T, S extends Geometry> SearchQuery<T, S> intersects(Rectangle r) {
        Preconditions.checkNotNull(r);
        Map<String, String> params = rectangleParams(r);
        Func1<Geometry, Boolean> condition = g -> g.intersects(r);
        return create(ID_INTERSECTS_RECTANGLE, params, condition, null);
    }

    /**
     * Returns an intersection query with the given circle.
     *
     * @param circle
     *            circle to intersect
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return query
     */
    public static <T, S extends Geometry> SearchQuery<T, S> intersects(Circle circle) {
        Preconditions.checkNotNull(circle);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("x", Double.toString(circle.x()));
        params.put("y", Double.toString(circle.y()));
        params.put("radius", Double.toString(circle.radius()));
        // node pruning uses the circle mbr, leaf refinement does exact tests
        Rectangle mbr = circle.mbr();
        Func1<Geometry, Boolean> condition = g -> g.intersects(mbr);
        @SuppressWarnings("unchecked")
        Func2<Geometry, Circle, Boolean> intersects =
                (Func2<Geometry, Circle, Boolean>) (Func2<?, ?, Boolean>)
                        Intersects.geometryIntersectsCircle;
        Func1<Entry<T, S>, Boolean> filter = e -> intersects.call(e.geometry(), circle);
        return create(ID_INTERSECTS_CIRCLE, params, condition, filter);
    }

    /**
     * Returns an intersection query with the given line.
     *
     * @param line
     *            line to intersect
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return query
     */
    public static <T, S extends Geometry> SearchQuery<T, S> intersects(Line line) {
        Preconditions.checkNotNull(line);
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("x1", Double.toString(line.x1()));
        params.put("y1", Double.toString(line.y1()));
        params.put("x2", Double.toString(line.x2()));
        params.put("y2", Double.toString(line.y2()));
        Rectangle mbr = line.mbr();
        Func1<Geometry, Boolean> condition = g -> g.intersects(mbr);
        @SuppressWarnings("unchecked")
        Func2<Geometry, Line, Boolean> intersects =
                (Func2<Geometry, Line, Boolean>) (Func2<?, ?, Boolean>)
                        Intersects.geometryIntersectsLine;
        Func1<Entry<T, S>, Boolean> filter = e -> intersects.call(e.geometry(), line);
        return create(ID_INTERSECTS_LINE, params, condition, filter);
    }

    /**
     * Returns a query for entries strictly less than {@code maxDistance} from
     * the given rectangle.
     *
     * @param r
     *            anchor rectangle
     * @param maxDistance
     *            strict maximum distance
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return query
     */
    public static <T, S extends Geometry> SearchQuery<T, S> within(Rectangle r,
            double maxDistance) {
        Preconditions.checkNotNull(r);
        Map<String, String> params = rectangleParams(r);
        params.put("maxDistance", Double.toString(maxDistance));
        Func1<Geometry, Boolean> condition = g -> g.distance(r) < maxDistance;
        return create(ID_WITHIN_DISTANCE_RECTANGLE, params, condition, null);
    }

    /**
     * Returns a query matching every entry (the query behind
     * {@link RTree#entries()}).
     *
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return query
     */
    public static <T, S extends Geometry> SearchQuery<T, S> all() {
        Func1<Geometry, Boolean> condition = g -> true;
        return create(ID_ALL, new LinkedHashMap<String, String>(), condition, null);
    }

    /**
     * Creates a custom query bound to the given id. The predicate closure is
     * never serialized: resuming its token requires passing the same query
     * (matching id and empty parameter map) to {@code searchPage}. This is
     * the escape hatch for predicates that cannot be expressed with the
     * built-in factories while remaining fail closed for any id mismatch.
     *
     * @param id
     *            stable query id chosen by the caller
     * @param nodeCondition
     *            conservative node and leaf geometry predicate
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return query
     */
    public static <T, S extends Geometry> SearchQuery<T, S> custom(String id,
            Func1<? super Geometry, Boolean> nodeCondition) {
        return custom(id, nodeCondition, null);
    }

    /**
     * Creates a custom query bound to the given id with an optional entry
     * refinement.
     *
     * @param id
     *            stable query id chosen by the caller
     * @param nodeCondition
     *            conservative node and leaf geometry predicate
     * @param entryFilter
     *            optional refinement on matched entries (may be {@code null})
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return query
     */
    public static <T, S extends Geometry> SearchQuery<T, S> custom(String id,
            Func1<? super Geometry, Boolean> nodeCondition,
            Func1<? super Entry<T, S>, Boolean> entryFilter) {
        Preconditions.checkNotNull(id);
        Preconditions.checkArgument(!id.isEmpty(), "id cannot be empty");
        Preconditions.checkNotNull(nodeCondition);
        return new SearchQueryImpl<T, S>(id, new LinkedHashMap<String, String>(), nodeCondition,
                entryFilter);
    }

    static <T, S extends Geometry> SearchQuery<T, S> create(String id,
            Map<String, String> parameters, Func1<? super Geometry, Boolean> nodeCondition,
            Func1<? super Entry<T, S>, Boolean> entryFilter) {
        return new SearchQueryImpl<T, S>(id, parameters, nodeCondition, entryFilter);
    }

    private static Map<String, String> rectangleParams(Rectangle r) {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("x1", Double.toString(r.x1()));
        params.put("y1", Double.toString(r.y1()));
        params.put("x2", Double.toString(r.x2()));
        params.put("y2", Double.toString(r.y2()));
        return params;
    }

    /**
     * Order-independent parameter comparison.
     */
    static boolean parametersEqual(Map<String, String> a, Map<String, String> b) {
        return new TreeMap<String, String>(a).equals(new TreeMap<String, String>(b));
    }

    private static final class SearchQueryImpl<T, S extends Geometry> extends SearchQuery<T, S> {

        private final String id;
        private final Map<String, String> parameters;
        private final Func1<? super Geometry, Boolean> nodeCondition;
        private final Func1<? super Entry<T, S>, Boolean> entryFilter;

        SearchQueryImpl(String id, Map<String, String> parameters,
                Func1<? super Geometry, Boolean> nodeCondition,
                Func1<? super Entry<T, S>, Boolean> entryFilter) {
            this.id = id;
            this.parameters = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(parameters));
            this.nodeCondition = nodeCondition;
            this.entryFilter = entryFilter;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Map<String, String> parameters() {
            return parameters;
        }

        @Override
        public Func1<? super Geometry, Boolean> nodeCondition() {
            return nodeCondition;
        }

        @Override
        public Func1<? super Entry<T, S>, Boolean> entryFilter() {
            return entryFilter;
        }

        @Override
        public String toString() {
            return "SearchQuery [id=" + id + ", parameters=" + parameters + "]";
        }
    }

}
