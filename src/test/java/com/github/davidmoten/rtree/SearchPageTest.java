package com.github.davidmoten.rtree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Point;
import com.github.davidmoten.rtree.geometry.Rectangle;

public class SearchPageTest {

    private static final Rectangle REGION = Geometries.rectangle(5, 5, 25, 40);

    private static Point pointOf(int i) {
        return Geometries.point(i % 37, (i * 31) % 53);
    }

    private static RTree<String, Point> createStarTree(int n) {
        RTree<String, Point> tree = RTree.star().maxChildren(4).create();
        for (int i = 0; i < n; i++) {
            tree = tree.add("v" + i, pointOf(i));
        }
        return tree;
    }

    private static RTree<String, Point> createQuadraticTree(int n) {
        RTree<String, Point> tree = RTree.maxChildren(4).create();
        for (int i = 0; i < n; i++) {
            tree = tree.add("v" + i, pointOf(i));
        }
        return tree;
    }

    private static List<Entry<String, Point>> drain(RTree<String, Point> tree, Rectangle r,
            int pageSize) {
        List<Entry<String, Point>> list = new ArrayList<Entry<String, Point>>();
        SearchPage<String, Point> page = tree.searchPage(r, pageSize);
        list.addAll(page.entries());
        while (page.hasNext()) {
            page = tree.searchPage(r, page.nextToken().get());
            list.addAll(page.entries());
        }
        return list;
    }

    private static List<Entry<String, Point>> oneShot(RTree<String, Point> tree, Rectangle r) {
        return tree.search(r).toList().toBlocking().single();
    }

    @Test
    public void testPagedConcatenationEqualsOneShotForManyPageSizesStar() {
        RTree<String, Point> tree = createStarTree(1000);
        List<Entry<String, Point>> expected = oneShot(tree, REGION);
        assertTrue(expected.size() > 100);
        for (int pageSize : new int[] { 1, 2, 3, 4, 5, 7, 8, 16, 64, 100, 10000 }) {
            assertEquals("pageSize=" + pageSize, expected, drain(tree, REGION, pageSize));
        }
    }

    @Test
    public void testPagedConcatenationEqualsOneShotForManyPageSizesQuadratic() {
        RTree<String, Point> tree = createQuadraticTree(1000);
        List<Entry<String, Point>> expected = oneShot(tree, REGION);
        assertTrue(expected.size() > 100);
        for (int pageSize : new int[] { 1, 3, 7, 10000 }) {
            assertEquals("pageSize=" + pageSize, expected, drain(tree, REGION, pageSize));
        }
    }

    @Test
    public void testExactPageBoundaryAtEndOfSearch() {
        RTree<String, Point> tree = createStarTree(1000);
        Rectangle all = Geometries.rectangle(-1, -1, 100, 100);
        List<Entry<String, Point>> expected = oneShot(tree, all);
        assertEquals(1000, expected.size());
        // 1000 is exactly divisible by 8 so the final page ends exactly at the
        // end of the traversal; no resume token may be issued for it
        List<Entry<String, Point>> drained = new ArrayList<Entry<String, Point>>();
        SearchPage<String, Point> page = tree.searchPage(all, 8);
        drained.addAll(page.entries());
        int pages = 1;
        while (page.hasNext()) {
            page = tree.searchPage(all, page.nextToken().get());
            drained.addAll(page.entries());
            pages++;
        }
        assertEquals(125, pages);
        assertEquals(expected, drained);
        assertFalse(page.hasNext());
    }

    @Test
    public void testEmptyTreeReturnsSingleEmptyPageWithoutToken() {
        RTree<String, Point> tree = RTree.create();
        SearchPage<String, Point> page = tree.searchPage(REGION, 10);
        assertTrue(page.entries().isEmpty());
        assertFalse(page.hasNext());
        assertFalse(page.nextToken().isPresent());
    }

    @Test
    public void testDuplicateGeometries() {
        RTree<String, Point> tree = RTree.star().maxChildren(4).create();
        Point p = Geometries.point(10, 10);
        for (int i = 0; i < 50; i++) {
            tree = tree.add("dup" + i, p);
        }
        List<Entry<String, Point>> expected = oneShot(tree, REGION);
        assertEquals(50, expected.size());
        for (int pageSize : new int[] { 1, 7, 50, 51 }) {
            assertEquals("pageSize=" + pageSize, expected, drain(tree, REGION, pageSize));
        }
    }

    @Test
    public void testTokenFailsClosedOnTreeModifiedByAdd() {
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        RTree<String, Point> modified = tree.add("extra", Geometries.point(1000, 1000));
        try {
            modified.searchPage(REGION, token);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("structural version"));
            assertTrue(e.getMessage().contains(String.valueOf(token.structuralVersion())));
            assertTrue(e.getMessage().contains(String.valueOf(modified.structuralVersion())));
        }
    }

    @Test
    public void testTokenFailsClosedOnTreeModifiedByDelete() {
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        RTree<String, Point> modified = tree.delete("v3", pointOf(3));
        try {
            modified.searchPage(REGION, token);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("structural version"));
        }
    }

    @Test
    public void testTokenFailsClosedWhenTreeModifiedButSizeUnchanged() {
        // the most dangerous counterexample: delete + add leaves size unchanged
        // so a size-based version check would wrongly accept the stale token
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        RTree<String, Point> modified = tree.delete("v3", pointOf(3)) //
                .add("replacement", Geometries.point(999, 999));
        assertEquals(tree.size(), modified.size());
        assertTrue(tree.structuralVersion() != modified.structuralVersion());
        try {
            modified.searchPage(REGION, token);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("structural version"));
        }
    }

    @Test
    public void testTokenFailsClosedOnDifferentPredicate() {
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        Rectangle other = Geometries.rectangle(6, 5, 25, 40);
        try {
            tree.searchPage(other, token);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("predicate"));
        }
    }

    @Test
    public void testTokenFailsClosedOnUnsupportedOrder() {
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        ResumeToken bad = new ResumeToken(token.structuralVersion(), token.predicate(),
                "ASCENDING_DISTANCE", token.pageSize(), token.path());
        try {
            tree.searchPage(REGION, bad);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("order"));
        }
    }

    @Test
    public void testTokenFailsClosedOnCorruptPath() {
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        int[] path = token.path();
        path[path.length - 1] = Integer.MAX_VALUE;
        ResumeToken bad = new ResumeToken(token.structuralVersion(), token.predicate(),
                token.order(), token.pageSize(), path);
        try {
            tree.searchPage(REGION, bad);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("path"));
        }
    }

    @Test
    public void testTokenFailsClosedOnPathDescendingThroughLeaf() {
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        int[] path = token.path();
        // extend the path so it must descend below a leaf
        int[] longer = Arrays.copyOf(path, path.length + 5);
        ResumeToken bad = new ResumeToken(token.structuralVersion(), token.predicate(),
                token.order(), token.pageSize(), longer);
        try {
            tree.searchPage(REGION, bad);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            // either a leaf descent or an out-of-bounds child index
            assertTrue(e.getMessage().contains("path"));
        }
    }

    @Test
    public void testTokenSurvivesJavaSerializationRoundTrip() throws IOException,
            ClassNotFoundException {
        RTree<String, Point> tree = createStarTree(1000);
        SearchPage<String, Point> first = tree.searchPage(REGION, 10);
        ResumeToken token = first.nextToken().get();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bytes);
        oos.writeObject(token);
        oos.close();
        ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        ResumeToken token2 = (ResumeToken) ois.readObject();
        ois.close();
        assertEquals(token, token2);
        // resume with the deserialized token and drain to the end
        List<Entry<String, Point>> rest = new ArrayList<Entry<String, Point>>();
        SearchPage<String, Point> page = tree.searchPage(REGION, token2);
        rest.addAll(page.entries());
        while (page.hasNext()) {
            page = tree.searchPage(REGION, page.nextToken().get());
            rest.addAll(page.entries());
        }
        List<Entry<String, Point>> expected = oneShot(tree, REGION);
        List<Entry<String, Point>> all = new ArrayList<Entry<String, Point>>(first.entries());
        all.addAll(rest);
        assertEquals(expected, all);
    }

    @Test
    public void testTokenWorksAcrossIdenticallyBuiltTreeInstances() {
        // symmetric entry point: a token issued by one tree instance is valid
        // for another instance with identical structure and content (the
        // 'other process' scenario)
        RTree<String, Point> treeA = createStarTree(1000);
        RTree<String, Point> treeB = createStarTree(1000);
        assertTrue(treeA != treeB);
        assertEquals(treeA.structuralVersion(), treeB.structuralVersion());
        SearchPage<String, Point> first = treeA.searchPage(REGION, 10);
        List<Entry<String, Point>> drained = new ArrayList<Entry<String, Point>>(first.entries());
        SearchPage<String, Point> page = treeB.searchPage(REGION, first.nextToken().get());
        drained.addAll(page.entries());
        while (page.hasNext()) {
            page = treeB.searchPage(REGION, page.nextToken().get());
            drained.addAll(page.entries());
        }
        assertEquals(oneShot(treeA, REGION), drained);
    }

    @Test
    public void testStructuralVersionChangesOnAddAndDelete() {
        RTree<String, Point> tree = createStarTree(100);
        long v = tree.structuralVersion();
        assertTrue(v != 0);
        assertTrue(tree.add("a", Geometries.point(500, 500)).structuralVersion() != v);
        assertTrue(tree.delete("v3", pointOf(3)).structuralVersion() != v);
        // unchanged tree keeps its version
        assertEquals(v, tree.structuralVersion());
    }

    @Test
    public void testPageSizeMustBePositive() {
        RTree<String, Point> tree = createStarTree(10);
        try {
            tree.searchPage(REGION, 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("pageSize"));
        }
        try {
            tree.searchPage(REGION, -1);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("pageSize"));
        }
    }

    @Test
    public void testTokenBindsVersionPredicateOrderPageSizeAndPath() {
        RTree<String, Point> tree = createStarTree(1000);
        ResumeToken token = tree.searchPage(REGION, 10).nextToken().get();
        assertEquals(tree.structuralVersion(), token.structuralVersion());
        assertEquals(SearchPager.predicateFingerprint(REGION), token.predicate());
        assertEquals(ResumeToken.ORDER_DFS, token.order());
        assertEquals(10, token.pageSize());
        assertTrue(token.path().length >= 1);
    }

    @Test
    public void testWholeResultFitsInOnePage() {
        RTree<String, Point> tree = createStarTree(100);
        SearchPage<String, Point> page = tree.searchPage(REGION, 100000);
        assertEquals(oneShot(tree, REGION), page.entries());
        assertFalse(page.hasNext());
    }

    @Test
    public void testSearchPageOnTreeWithoutRoot() {
        RTree<String, Point> tree = RTree.create();
        assertEquals(0, tree.size());
        SearchPage<String, Point> page = tree.searchPage(REGION, 3);
        assertEquals(0, page.entries().size());
        assertFalse(page.hasNext());
    }
}
