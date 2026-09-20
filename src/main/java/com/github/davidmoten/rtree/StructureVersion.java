package com.github.davidmoten.rtree;

import com.github.davidmoten.rtree.geometry.Circle;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Line;
import com.github.davidmoten.rtree.geometry.Point;
import com.github.davidmoten.rtree.geometry.Rectangle;

/**
 * Deterministic content fingerprint of an R-tree used as the structural
 * version bound on resumable query tokens.
 *
 * <p>
 * The fingerprint folds the node topology (node kind, child count and child
 * order), every node minimum bounding rectangle, every leaf entry geometry
 * (including its geometry kind and coordinates) and the identity hash of each
 * entry value into a 64 bit FNV-1a hash. It does not use Java object identity
 * ({@link System#identityHashCode(Object)}) and depends only on the content of
 * the tree, so two trees with identical logical structure and content produce
 * the same fingerprint. That is what allows a cursor paused in one process to
 * be resumed in another after, for example, a FlatBuffers round-trip.
 * Conversely any structural mutation (insert, delete, re-split producing a
 * different topology, reordering, changed geometry or changed value) changes
 * the fingerprint and tokens issued against the old version fail closed.
 */
public final class StructureVersion {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static final byte TAG_EMPTY = 0;
    private static final byte TAG_NON_LEAF = 1;
    private static final byte TAG_LEAF = 2;

    private StructureVersion() {
        // prevent instantiation
    }

    /**
     * Computes the structural version of the given tree. An empty tree always
     * has the same fingerprint.
     *
     * @param tree
     *            tree to fingerprint
     * @param <T>
     *            entry value type
     * @param <S>
     *            entry geometry type
     * @return 64 bit content fingerprint
     */
    public static <T, S extends Geometry> long of(RTree<T, S> tree) {
        if (tree.root().isPresent()) {
            Fnv hash = new Fnv();
            hashNode(hash, tree.root().get());
            return hash.value();
        } else {
            // empty trees are structurally identical
            Fnv hash = new Fnv();
            hash.update(TAG_EMPTY);
            return hash.value();
        }
    }

    private static <T, S extends Geometry> void hashNode(Fnv hash, Node<T, S> node) {
        if (node instanceof NonLeaf) {
            hash.update(TAG_NON_LEAF);
            NonLeaf<T, S> nonLeaf = (NonLeaf<T, S>) node;
            hashRectangle(hash, node.geometry().mbr());
            int count = nonLeaf.count();
            hashInt(hash, count);
            for (int i = 0; i < count; i++) {
                hashNode(hash, nonLeaf.child(i));
            }
        } else {
            Leaf<T, S> leaf = (Leaf<T, S>) node;
            hash.update(TAG_LEAF);
            hashRectangle(hash, node.geometry().mbr());
            int count = leaf.count();
            hashInt(hash, count);
            for (int i = 0; i < count; i++) {
                hashEntry(hash, leaf.entry(i));
            }
        }
    }

    private static <T, S extends Geometry> void hashEntry(Fnv hash, Entry<T, S> entry) {
        hashGeometry(hash, entry.geometry());
        T value = entry.value();
        if (value == null) {
            hash.update((byte) 0);
        } else {
            hash.update((byte) 1);
            // use value hashCode so that identical values produce identical
            // structural versions across processes and node implementations
            hashInt(hash, value.hashCode());
        }
    }

    private static void hashGeometry(Fnv hash, Geometry g) {
        Rectangle mbr = g.mbr();
        // precision tag distinguishes float and double geometries that
        // compare as equal (e.g. point(1.0f) vs point(1.0d))
        hash.update(g.isDoublePrecision() ? (byte) 1 : (byte) 0);
        // Must test Point before Rectangle because Point extends Rectangle.
        if (g instanceof Point) {
            hash.update((byte) 1);
            hashRectangle(hash, mbr);
        } else if (g instanceof Circle) {
            hash.update((byte) 2);
            Circle c = (Circle) g;
            hashRectangle(hash, mbr);
            hashDouble(hash, c.radius());
        } else if (g instanceof Line) {
            hash.update((byte) 3);
            hashRectangle(hash, mbr);
        } else if (g instanceof Rectangle) {
            hash.update((byte) 4);
            hashRectangle(hash, mbr);
        } else {
            // unknown geometry: fall back to mbr and type name
            hash.update((byte) 5);
            hashRectangle(hash, mbr);
            hashString(hash, g.getClass().getName());
        }
    }

    private static void hashRectangle(Fnv hash, Rectangle r) {
        hashDouble(hash, r.x1());
        hashDouble(hash, r.y1());
        hashDouble(hash, r.x2());
        hashDouble(hash, r.y2());
    }

    private static void hashInt(Fnv hash, int value) {
        hash.update((byte) (value & 0xff));
        hash.update((byte) ((value >>> 8) & 0xff));
        hash.update((byte) ((value >>> 16) & 0xff));
        hash.update((byte) ((value >>> 24) & 0xff));
    }

    private static void hashLong(Fnv hash, long value) {
        for (int i = 0; i < 8; i++) {
            hash.update((byte) (value & 0xff));
            value >>>= 8;
        }
    }

    private static void hashDouble(Fnv hash, double value) {
        hashLong(hash, Double.doubleToLongBits(value));
    }

    private static void hashString(Fnv hash, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        hashInt(hash, bytes.length);
        hash.update(bytes);
    }

    /**
     * Streaming FNV-1a 64 bit accumulator. Package-private for reuse by the
     * token codec.
     */
    static final class Fnv {
        private long value = FNV_OFFSET_BASIS;

        void update(byte b) {
            value ^= (b & 0xffL);
            value *= FNV_PRIME;
        }

        void update(byte[] bytes) {
            for (byte b : bytes) {
                update(b);
            }
        }

        long value() {
            return value;
        }
    }

}
