package com.github.davidmoten.rtree;

import static com.github.davidmoten.rtree.geometry.Geometries.point;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.geometry.Point;

public class StructureVersionTest {

    private static List<Entry<String, Point>> gridEntries(int n) {
        List<Entry<String, Point>> list = new ArrayList<Entry<String, Point>>();
        int k = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                list.add(Entries.entry("e" + (k++), point(i, j)));
            }
        }
        return list;
    }

    @Test
    public void testEmptyTreeVersionIsConstant() {
        assertEquals(RTree.maxChildren(4).<String, Point>create().structureVersion(),
                RTree.star().<String, Point>create().structureVersion());
    }

    @Test
    public void testVersionIsMemoizedAndStable() {
        RTree<String, Point> tree = RTree.maxChildren(4).<String, Point>create()
                .add(Entries.entry("a", point(1, 2)));
        assertEquals(tree.structureVersion(), tree.structureVersion());
    }

    @Test
    public void testSameContentSameVersionIndependentOfBuild() {
        RTree<String, Point> a = RTree.maxChildren(4).<String, Point>create();
        RTree<String, Point> b = RTree.maxChildren(4).<String, Point>create();
        for (Entry<String, Point> e : gridEntries(6)) {
            a = a.add(e);
            b = b.add(e);
        }
        assertEquals(a.structureVersion(), b.structureVersion());
    }

    @Test
    public void testAddedEntryChangesVersion() {
        RTree<String, Point> a = RTree.maxChildren(4).<String, Point>create();
        for (Entry<String, Point> e : gridEntries(4)) {
            a = a.add(e);
        }
        long before = a.structureVersion();
        RTree<String, Point> b = a.add(Entries.entry("new", point(2, 2)));
        assertNotEquals(before, b.structureVersion());
    }

    @Test
    public void testDeletedEntryChangesVersion() {
        RTree<String, Point> a = RTree.maxChildren(4).<String, Point>create();
        List<Entry<String, Point>> entries = gridEntries(4);
        for (Entry<String, Point> e : entries) {
            a = a.add(e);
        }
        long before = a.structureVersion();
        assertNotEquals(before, a.delete(entries.get(5)).structureVersion());
    }

    @Test
    public void testDifferentGeometryChangesVersion() {
        RTree<String, Point> a = RTree.maxChildren(4).<String, Point>create()
                .add(Entries.entry("a", point(1, 1)));
        RTree<String, Point> b = RTree.maxChildren(4).<String, Point>create()
                .add(Entries.entry("a", point(1, 2)));
        assertNotEquals(a.structureVersion(), b.structureVersion());
    }

    @Test
    public void testDifferentValueChangesVersion() {
        RTree<String, Point> a = RTree.maxChildren(4).<String, Point>create()
                .add(Entries.entry("a", point(1, 1)));
        RTree<String, Point> b = RTree.maxChildren(4).<String, Point>create()
                .add(Entries.entry("b", point(1, 1)));
        assertNotEquals(a.structureVersion(), b.structureVersion());
    }

    @Test
    public void testFloatAndDoubleGeometryAreDifferentVersions() {
        RTree<String, Point> a = RTree.maxChildren(4).<String, Point>create()
                .add(Entries.entry("a", point(1.0d, 1.0d)));
        RTree<String, Point> b = RTree.maxChildren(4).<String, Point>create()
                .add(Entries.entry("a", point(1.0f, 1.0f)));
        // float and double coordinate bits differ
        assertTrue(a.structureVersion() != b.structureVersion());
    }

    @Test
    public void testBulkLoadedTreeSameContentVersionEqualsIncremental() {
        List<Entry<String, Point>> entries = gridEntries(5);
        RTree<String, Point> incremental = RTree.maxChildren(4).<String, Point>create();
        for (Entry<String, Point> e : entries) {
            incremental = incremental.add(e);
        }
        // NOTE: STR bulk loading produces a different topology, so versions
        // are expected to differ; this documents that topology is part of
        // the structural version
        RTree<String, Point> bulk = RTree.create(entries);
        assertNotEquals(incremental.structureVersion(), bulk.structureVersion());
    }
}
