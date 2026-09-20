package com.github.davidmoten.rtree;

import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;

/**
 * Immutable ordering descriptor for a paged spatial query. The sort
 * parameters are bound to the resume token: resuming a token with a different
 * sort (or a different distance anchor) fails closed.
 */
public final class Sort<T, S extends Geometry> {

    enum Type {
        /** Natural depth-first traversal order emitted by the R-tree search. */
        NONE,
        /** Ascending distance from an anchor rectangle. */
        ASCENDING_DISTANCE
    }

    private final Type type;
    private final Rectangle anchor;

    private Sort(Type type, Rectangle anchor) {
        this.type = type;
        this.anchor = anchor;
    }

    /**
     * Returns the natural depth-first order used by
     * {@link RTree#entries()} and {@code search}. This is the default and
     * preserves the exact order of the existing (non paged) API.
     *
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return no-sort descriptor
     */
    public static <T, S extends Geometry> Sort<T, S> none() {
        return new Sort<T, S>(Type.NONE, null);
    }

    /**
     * Returns an ascending distance ordering from the given anchor
     * rectangle. Ties are broken deterministically by the natural depth-first
     * ordinal of the entries so the ordering is total and stable across
     * paging and across tree structure implementations.
     *
     * @param anchor
     *            rectangle distances are measured from
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return ascending distance sort descriptor
     */
    public static <T, S extends Geometry> Sort<T, S> ascendingDistance(Rectangle anchor) {
        if (anchor == null) {
            throw new IllegalArgumentException("anchor cannot be null");
        }
        return new Sort<T, S>(Type.ASCENDING_DISTANCE, anchor);
    }

    Type type() {
        return type;
    }

    boolean isNone() {
        return type == Type.NONE;
    }

    Rectangle anchor() {
        return anchor;
    }

    /**
     * Returns a stable string identity for the sort used inside resume tokens.
     *
     * @return canonical sort identity
     */
    String toTokenString() {
        if (type == Type.NONE) {
            return "none";
        } else {
            return "ascDist:" + rectangleToString(anchor);
        }
    }

    static String rectangleToString(Rectangle r) {
        return Double.doubleToLongBits(r.x1()) + "|" + Double.doubleToLongBits(r.y1()) + "|"
                + Double.doubleToLongBits(r.x2()) + "|" + Double.doubleToLongBits(r.y2());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (!(obj instanceof Sort)) {
            return false;
        } else {
            Sort<?, ?> other = (Sort<?, ?>) obj;
            return type == other.type && anchor == null ? other.anchor == null
                    : anchor.equals(other.anchor);
        }
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + (anchor == null ? 0 : anchor.hashCode());
        return result;
    }

    @Override
    public String toString() {
        if (type == Type.NONE) {
            return "Sort [none]";
        } else {
            return "Sort [ascendingDistance anchor=" + anchor + "]";
        }
    }

}
