package com.github.davidmoten.rtree;

import java.io.Serializable;
import java.util.Arrays;

/**
 * An opaque, serializable marker of a position within a stable depth-first
 * traversal of an {@link RTree}. A token is issued by
 * {@link RTree#searchPage(com.github.davidmoten.rtree.geometry.Rectangle, int)}
 * via {@link SearchPage#nextToken()} and is presented to
 * {@link RTree#searchPage(com.github.davidmoten.rtree.geometry.Rectangle, ResumeToken)}
 * to obtain the next page.
 *
 * <p>
 * A token binds:
 *
 * <ul>
 * <li>the structural version of the tree (see {@link RTree#structuralVersion()})</li>
 * <li>a fingerprint of the geometry predicate</li>
 * <li>the traversal order (sort parameters)</li>
 * <li>the page size</li>
 * <li>the current depth-first-search stack as a path of child indices from the
 * root</li>
 * </ul>
 *
 * <p>
 * Presenting a token against a modified tree, a different predicate or a
 * different order fails closed with a {@link ResumeTokenException}; no results
 * are returned in that case.
 */
public final class ResumeToken implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The only traversal order currently supported by paged search: the same
     * deterministic depth-first pre-order used by
     * {@link RTree#search(com.github.davidmoten.rtree.geometry.Rectangle)}.
     */
    public static final String ORDER_DFS = "DFS_PREORDER";

    private final long structuralVersion;
    private final String predicate;
    private final String order;
    private final int pageSize;
    private final int[] path;

    /**
     * Constructor.
     *
     * @param structuralVersion
     *            structural version of the tree the token is bound to
     * @param predicate
     *            fingerprint of the geometry predicate
     * @param order
     *            traversal order identifier
     * @param pageSize
     *            page size (must be positive)
     * @param path
     *            positions of the depth-first-search stack frames from the root
     *            (first element) to the current node (last element)
     */
    public ResumeToken(long structuralVersion, String predicate, String order, int pageSize,
            int[] path) {
        if (predicate == null) {
            throw new IllegalArgumentException("predicate cannot be null");
        }
        if (order == null) {
            throw new IllegalArgumentException("order cannot be null");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize must be positive but was " + pageSize);
        }
        if (path == null || path.length == 0) {
            throw new IllegalArgumentException("path cannot be null or empty");
        }
        this.structuralVersion = structuralVersion;
        this.predicate = predicate;
        this.order = order;
        this.pageSize = pageSize;
        this.path = Arrays.copyOf(path, path.length);
    }

    /**
     * Returns the structural version of the tree this token is bound to.
     *
     * @return structural version
     */
    public long structuralVersion() {
        return structuralVersion;
    }

    /**
     * Returns the fingerprint of the geometry predicate this token is bound to.
     *
     * @return predicate fingerprint
     */
    public String predicate() {
        return predicate;
    }

    /**
     * Returns the traversal order (sort parameters) this token is bound to.
     *
     * @return order identifier
     */
    public String order() {
        return order;
    }

    /**
     * Returns the page size this token was created with.
     *
     * @return page size
     */
    public int pageSize() {
        return pageSize;
    }

    /**
     * Returns a copy of the depth-first-search stack path (child indices from
     * the root to the current node).
     *
     * @return path of child indices
     */
    public int[] path() {
        return Arrays.copyOf(path, path.length);
    }

    @Override
    public String toString() {
        return "ResumeToken [structuralVersion=" + structuralVersion + ", predicate=" + predicate
                + ", order=" + order + ", pageSize=" + pageSize + ", path="
                + Arrays.toString(path) + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (structuralVersion ^ (structuralVersion >>> 32));
        result = prime * result + predicate.hashCode();
        result = prime * result + order.hashCode();
        result = prime * result + pageSize;
        result = prime * result + Arrays.hashCode(path);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof ResumeToken)) {
            return false;
        }
        ResumeToken other = (ResumeToken) obj;
        return structuralVersion == other.structuralVersion && predicate.equals(other.predicate)
                && order.equals(other.order) && pageSize == other.pageSize
                && Arrays.equals(path, other.path);
    }
}
