package com.github.davidmoten.rtree;

import java.util.Optional;

import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;

/**
 * Computes a deterministic 64-bit fingerprint of the structure and geometry
 * content of an R-tree. The fingerprint is a function only of:
 *
 * <ul>
 * <li>tree size</li>
 * <li>maxChildren and minChildren configuration</li>
 * <li>the shape of the node hierarchy (child counts, in depth-first order)</li>
 * <li>the minimum bounding rectangle of every node</li>
 * <li>the geometry type and minimum bounding rectangle of every entry</li>
 * </ul>
 *
 * <p>
 * Because the fingerprint is derived from content rather than from identity or
 * from a mutation counter, two trees with identical content (for example an
 * R-tree and its FlatBuffers serialization round-trip, or two trees built in
 * different processes with the same insertions) share the same structural
 * version, while any structural mutation (add, delete, split) yields a
 * different version with probability 1 - 2^-64.
 */
final class StructuralVersion {

    // FNV-1a 64-bit constants
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final long LEAF_MARKER = 0x4c4541464c454146L; // "LEAFLEAF"
    private static final long NON_LEAF_MARKER = 0x4e4f4e4c45414621L;
    private static final long EMPTY_TREE_MARKER = 0x9e3779b97f4a7c15L;

    private StructuralVersion() {
        // prevent instantiation
    }

    static <T, S extends Geometry> long compute(Optional<? extends Node<T, S>> root, int size,
            Context<T, S> context) {
        long h = OFFSET_BASIS;
        h = mix(h, size);
        if (context == null) {
            h = mix(h, -1);
            h = mix(h, -1);
        } else {
            h = mix(h, context.maxChildren());
            h = mix(h, context.minChildren());
        }
        if (root.isPresent()) {
            return mixNode(h, root.get());
        } else {
            return mix(h, EMPTY_TREE_MARKER);
        }
    }

    private static <T, S extends Geometry> long mixNode(long h, Node<T, S> node) {
        h = mix(h, node.count());
        h = mixMbr(h, node.geometry().mbr());
        if (node instanceof Leaf) {
            h = mix(h, LEAF_MARKER);
            Leaf<T, S> leaf = (Leaf<T, S>) node;
            for (int i = 0; i < leaf.count(); i++) {
                Geometry g = leaf.entry(i).geometry();
                h = mix(h, g.getClass().getName().hashCode());
                h = mixMbr(h, g.mbr());
            }
            return h;
        } else {
            h = mix(h, NON_LEAF_MARKER);
            NonLeaf<T, S> nonLeaf = (NonLeaf<T, S>) node;
            for (int i = 0; i < nonLeaf.count(); i++) {
                h = mixNode(h, nonLeaf.child(i));
            }
            return h;
        }
    }

    private static long mixMbr(long h, Rectangle r) {
        h = mix(h, r.isDoublePrecision() ? 1 : 0);
        h = mix(h, Double.doubleToLongBits(r.x1()));
        h = mix(h, Double.doubleToLongBits(r.y1()));
        h = mix(h, Double.doubleToLongBits(r.x2()));
        h = mix(h, Double.doubleToLongBits(r.y2()));
        return h;
    }

    private static long mix(long h, long v) {
        h ^= v;
        h *= PRIME;
        return h;
    }
}
