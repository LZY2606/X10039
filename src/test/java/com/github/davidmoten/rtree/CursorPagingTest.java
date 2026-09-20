package com.github.davidmoten.rtree;

import static com.github.davidmoten.rtree.geometry.Geometries.circle;
import static com.github.davidmoten.rtree.geometry.Geometries.line;
import static com.github.davidmoten.rtree.geometry.Geometries.point;
import static com.github.davidmoten.rtree.geometry.Geometries.rectangle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.Test;

import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Point;
import com.github.davidmoten.rtree.geometry.Rectangle;

import rx.functions.Func1;

/**
 * Tests for resumable stable spatial query cursors. Coverage is organized
 * around the three required directions: boundary conditions (every page size
 * from 1, empty tree, duplicates), symmetric entry points (rectangle/point/
 * circle/line/all/custom and default versus sorted) and failure recovery
 * (modified tree, changed predicate, changed sort, corrupt tokens).
 */
public class CursorPagingTest {

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    /** Deterministic pseudo random sequence (xorshift) - no Math.random. */
    private static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        }

        long nextLong() {
            long x = state;
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            state = x;
            return x;
        }

        double nextDouble() {
            return (nextLong() >>> 11) * 0x1.0p-53;
        }
    }

    private static Entry<String, Point> pe(int i, double x, double y) {
        return Entries.entry("p" + i, point(x, y));
    }

    private static RTree<String, Point> gridTree(int cols, int rows) {
        RTree<String, Point> tree = RTree.maxChildren(4).create();
        int n = 0;
        for (int i = 0; i < cols; i++) {
            for (int j = 0; j < rows; j++) {
                tree = tree.add(pe(n++, i, j));
            }
        }
        return tree;
    }

    private static RTree<String, Point> randomTree(int count, long seed, boolean star) {
        Rng rng = new Rng(seed);
        RTree<String, Point> tree;
        if (star) {
            tree = RTree.star().maxChildren(4).create();
        } else {
            tree = RTree.maxChildren(4).create();
        }
        for (int i = 0; i < count; i++) {
            double x = Math.floor(rng.nextDouble() * 20);
            double y = Math.floor(rng.nextDouble() * 20);
            tree = tree.add(pe(i, x, y));
        }
        return tree;
    }

    private static <T, S extends Geometry> List<Entry<T, S>> collectAllDfs(
            RTree<T, S> tree, SearchQuery<T, S> query) {
        List<Entry<T, S>> all = new ArrayList<Entry<T, S>>();
        ResumeToken token = null;
        do {
            Page<T, S> page = tree.searchPage(query, token, 1 + (all.size() * 7) % 13);
            all.addAll(page.entries());
            Optional<ResumeToken> next = page.nextResumeToken();
            token = next.orElse(null);
        } while (token != null);
        return all;
    }

    /**
     * Core invariant: for every page size from 1 to n+1 the concatenation of
     * pages is exactly the one-shot query result, item by item.
     */
    private static <T, S extends Geometry> void assertPagingEqualsOneShot(RTree<T, S> tree,
            SearchQuery<T, S> query, List<Entry<T, S>> oneShot) {
        int n = oneShot.size();
        int maxPage = n + 1;
        for (int pageSize = 1; pageSize <= maxPage; pageSize++) {
            List<Entry<T, S>> collected = new ArrayList<Entry<T, S>>();
            ResumeToken token = null;
            int pageCount = 0;
            boolean sawPartial = false;
            do {
                Page<T, S> page = tree.searchPage(query, token, pageSize);
                assertTrue("page larger than requested",
                        page.entries().size() <= pageSize);
                if (page.entries().size() < pageSize && page.hasNext()) {
                    sawPartial = true;
                }
                collected.addAll(page.entries());
                pageCount++;
                token = page.nextResumeToken().orElse(null);
                if (pageCount > n + 5) {
                    fail("paging did not terminate at pageSize " + pageSize);
                }
            } while (token != null);
            assertEquals("pageSize=" + pageSize, oneShot, collected);
            // boundaries: first page of size 1 and last-page exhaustion
        }
        // n == 0 : first page must be empty and terminal
        if (n == 0) {
            Page<T, S> first = tree.searchPage(query, null, 5);
            assertTrue(first.entries().isEmpty());
            assertFalse(first.hasNext());
            assertNull(first.nextResumeToken().orElse(null));
        }
    }

    private static RTree<String, Point> roundTripFlatBuffers(RTree<String, Point> tree)
            throws IOException {
        Serializer<String, Point> serializer = Serializers.flatBuffers().utf8();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        serializer.write(tree, os);
        os.close();
        byte[] bytes = os.toByteArray();
        return serializer.read(new ByteArrayInputStream(bytes), bytes.length,
                InternalStructure.SINGLE_ARRAY);
    }

    // ---------------------------------------------------------------
    // boundary: empty tree
    // ---------------------------------------------------------------

    @Test
    public void testEmptyTreeDfsPagingIsTerminal() {
        RTree<String, Point> tree = RTree.maxChildren(4).create();
        Page<String, Point> page = tree.searchPage(rectangle(0, 0, 10, 10), 3);
        assertTrue(page.entries().isEmpty());
        assertFalse(page.hasNext());
        assertEquals(0, tree.size());
    }

    @Test
    public void testEmptyTreeSortedPagingIsTerminal() {
        RTree<String, Point> tree = RTree.maxChildren(4).create();
        Page<String, Point> page = tree.searchPage(SearchQuery.<String, Point>all(),
                Sort.<String, Point>ascendingDistance(rectangle(0, 0, 1, 1)), null, 3);
        assertTrue(page.entries().isEmpty());
        assertFalse(page.hasNext());
    }

    @Test
    public void testEmptyTreeStructureVersionStable() {
        RTree<String, Point> a = RTree.maxChildren(4).create();
        RTree<String, Point> b = RTree.star().<String, Point>create();
        assertEquals(a.structureVersion(), b.structureVersion());
    }

    // ---------------------------------------------------------------
    // boundary: every page size, item by item equality to one-shot
    // ---------------------------------------------------------------

    @Test
    public void testAllEntriesPagingEqualsOneShotForEveryPageSizeGrid() {
        RTree<String, Point> tree = gridTree(5, 4);
        List<Entry<String, Point>> oneShot = tree.entries().toList().toBlocking().single();
        assertEquals(20, oneShot.size());
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>all(), oneShot);
    }

    @Test
    public void testRectangleSearchPagingEqualsOneShotEveryPageSizeRandomQuadratic() {
        RTree<String, Point> tree = randomTree(120, 42L, false);
        Rectangle window = rectangle(3, 4, 12, 15);
        List<Entry<String, Point>> oneShot = tree.search(window).toList().toBlocking().single();
        assertTrue(oneShot.size() > 1);
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>intersects(window), oneShot);
    }

    @Test
    public void testRectangleSearchPagingEqualsOneShotEveryPageSizeRStar() {
        RTree<String, Point> tree = randomTree(150, 777L, true);
        Rectangle window = rectangle(2, 2, 16, 18);
        List<Entry<String, Point>> oneShot = tree.search(window).toList().toBlocking().single();
        assertTrue(oneShot.size() > 10);
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>intersects(window), oneShot);
    }

    @Test
    public void testSearchWithNoMatchesEveryPageSize() {
        RTree<String, Point> tree = randomTree(60, 9L, false);
        Rectangle window = rectangle(1000, 1000, 1001, 1001);
        List<Entry<String, Point>> oneShot = tree.search(window).toList().toBlocking().single();
        assertTrue(oneShot.isEmpty());
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>intersects(window), oneShot);
    }

    @Test
    public void testWithinDistancePagingEqualsOneShotEveryPageSize() {
        RTree<String, Point> tree = randomTree(100, 123L, false);
        Rectangle anchor = rectangle(5, 5, 6, 6);
        double maxDistance = 4.5;
        List<Entry<String, Point>> oneShot = tree.search(anchor, maxDistance).toList()
                .toBlocking().single();
        assertTrue(oneShot.size() > 1);
        assertPagingEqualsOneShot(tree,
                SearchQuery.<String, Point>within(anchor, maxDistance), oneShot);
    }

    @Test
    public void testLastPageFullySizedAndNoTrailingToken() {
        RTree<String, Point> tree = gridTree(3, 3);
        List<Entry<String, Point>> oneShot = tree.entries().toList().toBlocking().single();
        // 9 entries, page size 3 -> exactly 3 full pages, no token after
        ResumeToken token = null;
        for (int page = 0; page < 3; page++) {
            Page<String, Point> p = tree.searchPage(SearchQuery.<String, Point>all(), token, 3);
            assertEquals(3, p.entries().size());
            assertEquals("page " + page, page < 2, p.hasNext());
            token = p.nextResumeToken().orElse(null);
        }
        assertNull(token);
        List<Entry<String, Point>> collected = collectAllDfs(tree,
                SearchQuery.<String, Point>all());
        assertEquals(oneShot, collected);
    }

    // ---------------------------------------------------------------
    // symmetric entry points
    // ---------------------------------------------------------------

    @Test
    public void testPointAndRectangleOverloadAreSymmetric() {
        RTree<String, Point> tree = randomTree(80, 31L, false);
        // point p(mbr of point == zero-area rectangle) must match the same
        // entries as rectangle search with the point's mbr
        Point p = point(7, 8);
        List<Entry<String, Point>> viaPoint = collectOverloads(tree, p);
        List<Entry<String, Point>> viaRectangle =
                collectAllDfs(tree, SearchQuery.<String, Point>intersects(p.mbr()));
        List<Entry<String, Point>> oneShot = tree.search(p).toList().toBlocking().single();
        assertEquals(oneShot, viaPoint);
        assertEquals(viaRectangle, viaPoint);
    }

    private static List<Entry<String, Point>> collectOverloads(RTree<String, Point> tree,
            Point p) {
        List<Entry<String, Point>> all = new ArrayList<Entry<String, Point>>();
        ResumeToken token = null;
        do {
            Page<String, Point> page = tree.searchPage(p, 2, token);
            all.addAll(page.entries());
            token = page.nextResumeToken().orElse(null);
        } while (token != null);
        return all;
    }

    @Test
    public void testCircleSearchPagingEqualsOneShot() {
        RTree<String, Point> tree = randomTree(100, 55L, false);
        com.github.davidmoten.rtree.geometry.Circle c = circle(8, 9, 5);
        List<Entry<String, Point>> oneShot = tree.search(c).toList().toBlocking().single();
        assertTrue(oneShot.size() > 1);
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>intersects(c), oneShot);
    }

    @Test
    public void testLineSearchPagingEqualsOneShot() {
        RTree<String, Point> tree = randomTree(100, 66L, true);
        com.github.davidmoten.rtree.geometry.Line l = line(0, 0, 18, 16);
        List<Entry<String, Point>> oneShot = tree.search(l).toList().toBlocking().single();
        assertTrue(oneShot.size() > 0);
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>intersects(l), oneShot);
    }

    @Test
    public void testCustomPredicatePagingEqualsOneShot() {
        RTree<String, Point> tree = randomTree(90, 88L, false);
        // custom conservative predicate: only points in the x==5 column.
        // Node mbrs pass when their x-range includes 5.
        Rectangle prune = rectangle(5, 0, 5, 19);
        Func1<Geometry, Boolean> condition = g -> g.mbr().intersects(prune)
                && (g instanceof Point ? ((Point) g).x() == 5.0 : true);
        SearchQuery<String, Point> q = SearchQuery.custom("x-equals-5", condition);
        List<Entry<String, Point>> oneShot = new ArrayList<Entry<String, Point>>();
        for (Entry<String, Point> e : tree.entries().toList().toBlocking().single()) {
            if (condition.call(e.geometry())) {
                oneShot.add(e);
            }
        }
        assertTrue(oneShot.size() > 0);
        assertPagingEqualsOneShot(tree, q, oneShot);
    }

    @Test
    public void testSortedDistancePagingEqualsStableOneShotEveryPageSize() {
        RTree<String, Point> tree = randomTree(130, 202L, false);
        Rectangle anchor = rectangle(6, 7, 7, 8);
        Sort<String, Point> sort = Sort.ascendingDistance(anchor);
        // expected = stable sort of DFS result by ascending distance
        List<Entry<String, Point>> dfs = tree.search(rectangle(-100, -100, 100, 100))
                .toList().toBlocking().single();
        List<Entry<String, Point>> expected = new ArrayList<Entry<String, Point>>(dfs);
        java.util.Collections.sort(expected,
                com.github.davidmoten.rtree.internal.Comparators
                        .<String, Point>ascendingDistance(anchor));
        int n = expected.size();
        for (int pageSize = 1; pageSize <= n + 1; pageSize++) {
            List<Entry<String, Point>> collected = new ArrayList<Entry<String, Point>>();
            ResumeToken token = null;
            int pages = 0;
            do {
                Page<String, Point> page = tree.searchPage(
                        SearchQuery.<String, Point>all(), sort, token, pageSize);
                collected.addAll(page.entries());
                token = page.nextResumeToken().orElse(null);
                assertTrue(pages++ <= n + 5);
            } while (token != null);
            assertEquals("sorted pageSize=" + pageSize, expected, collected);
        }
    }

    @Test
    public void testSortedDistanceTiesBrokenByDfsOrdinalStably() {
        // duplicate geometries => identical distances; order must stay DFS
        RTree<String, Point> tree = RTree.maxChildren(3).<String, Point>create()
                .add(Entries.entry("a", point(0, 0)))
                .add(Entries.entry("b", point(0, 0)))
                .add(Entries.entry("c", point(0, 0)))
                .add(Entries.entry("d", point(5, 0)));
        Rectangle anchor = rectangle(10, 0, 11, 1);
        Sort<String, Point> sort = Sort.ascendingDistance(anchor);
        List<Entry<String, Point>> expected = tree.entries().toList().toBlocking().single();
        java.util.Collections.sort(expected,
                com.github.davidmoten.rtree.internal.Comparators
                        .<String, Point>ascendingDistance(anchor));
        List<Entry<String, Point>> collected = new ArrayList<Entry<String, Point>>();
        ResumeToken token = null;
        do {
            Page<String, Point> page = tree.searchPage(SearchQuery.<String, Point>all(), sort,
                    token, 1);
            collected.addAll(page.entries());
            token = page.nextResumeToken().orElse(null);
        } while (token != null);
        assertEquals(expected, collected);
        // d is strictly closer; the three tied duplicates appear in their
        // stable DFS ordinal order (not insertion order - leaf splits
        // reorder). Build the expectation from the actual one-shot DFS
        // order so the test asserts stability rather than a hardcoded order.
        assertEquals("d", collected.get(0).value());
        List<String> dfsValues = new ArrayList<String>();
        for (Entry<String, Point> e : tree.entries().toList().toBlocking().single()) {
            if (!"d".equals(e.value())) {
                dfsValues.add(e.value());
            }
        }
        assertEquals(dfsValues, valuesInOrder(collected).subList(1, 4));
        // and the DFS duplicates are all present exactly once
        assertTrue(valuesInOrder(collected).containsAll(java.util.Arrays.asList("a", "b", "c")));
    }

    private static <T, S extends Geometry> List<T> valuesInOrder(List<Entry<T, S>> entries) {
        List<T> values = new ArrayList<T>();
        for (Entry<T, S> e : entries) {
            values.add(e.value());
        }
        return values;
    }

    // ---------------------------------------------------------------
    // failure recovery (fail closed)
    // ---------------------------------------------------------------

    @Test
    public void testResumeAfterTreeModificationFailsClosed() {
        RTree<String, Point> tree = gridTree(4, 4);
        Rectangle window = rectangle(0, 0, 3, 3);
        Page<String, Point> first = tree.searchPage(window, 2);
        ResumeToken token = first.nextResumeToken().get();
        RTree<String, Point> modified = tree.add(pe(999, 1, 1));
        try {
            modified.searchPage(window, 2, token);
            fail("expected ResumeTokenException for modified tree");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testResumeAfterDeleteFailsClosed() {
        RTree<String, Point> tree = gridTree(4, 4);
        Rectangle window = rectangle(0, 0, 3, 3);
        ResumeToken token = tree.searchPage(window, 2).nextResumeToken().get();
        Entry<String, Point> firstEntry = tree.search(window).toList().toBlocking().single()
                .get(0);
        RTree<String, Point> modified = tree.delete(firstEntry);
        try {
            modified.searchPage(window, 2, token);
            fail("expected ResumeTokenException after delete");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testResumeWithDifferentRectanglePredicateFailsClosed() {
        RTree<String, Point> tree = gridTree(5, 5);
        ResumeToken token = tree.searchPage(rectangle(0, 0, 2, 2), 2).nextResumeToken().get();
        try {
            tree.searchPage(rectangle(1, 1, 4, 4), 2, token);
            fail("expected ResumeTokenException for different predicate");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testResumeWithDifferentQueryKindFailsClosed() {
        RTree<String, Point> tree = randomTree(80, 5L, false);
        ResumeToken token = tree
                .searchPage(SearchQuery.<String, Point>within(rectangle(5, 5, 6, 6), 5.0),
                        (ResumeToken) null, 3)
                .nextResumeToken().get();
        try {
            tree.searchPage(SearchQuery.<String, Point>intersects(rectangle(5, 5, 6, 6)), token,
                    3);
            fail("expected ResumeTokenException for different query kind");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testResumeCustomQueryWithDifferentQueryIdFailsClosed() {
        RTree<String, Point> tree = randomTree(80, 6L, false);
        Func1<Geometry, Boolean> always = g -> true;
        SearchQuery<String, Point> q1 = SearchQuery.custom("query-one", always);
        SearchQuery<String, Point> q2 = SearchQuery.custom("query-two", always);
        ResumeToken token = tree.searchPage(q1, (ResumeToken) null, 3).nextResumeToken().get();
        try {
            tree.searchPage(q2, token, 3);
            fail("expected ResumeTokenException for different custom query id");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testResumeWithDifferentSortFailsClosed() {
        RTree<String, Point> tree = randomTree(80, 7L, false);
        SearchQuery<String, Point> q = SearchQuery.<String, Point>all();
        Sort<String, Point> sort1 = Sort.ascendingDistance(rectangle(0, 0, 1, 1));
        Sort<String, Point> sort2 = Sort.ascendingDistance(rectangle(9, 9, 10, 10));
        ResumeToken token = tree.searchPage(q, sort1, null, 3).nextResumeToken().get();
        try {
            tree.searchPage(q, sort2, token, 3);
            fail("expected ResumeTokenException for different sort anchor");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testResumeSortedTokenWithDfsSortFailsClosed() {
        RTree<String, Point> tree = randomTree(60, 8L, false);
        SearchQuery<String, Point> q = SearchQuery.<String, Point>all();
        Sort<String, Point> sort = Sort.ascendingDistance(rectangle(0, 0, 1, 1));
        ResumeToken token = tree.searchPage(q, sort, null, 3).nextResumeToken().get();
        try {
            tree.searchPage(q, Sort.<String, Point>none(), token, 3);
            fail("expected ResumeTokenException mixing sorted/unsorted");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testResumeDfsTokenWithSortedSortFailsClosed() {
        RTree<String, Point> tree = randomTree(60, 10L, false);
        SearchQuery<String, Point> q = SearchQuery.<String, Point>all();
        ResumeToken token = tree.searchPage(q, null, 3).nextResumeToken().get();
        try {
            tree.searchPage(q, Sort.<String, Point>ascendingDistance(rectangle(0, 0, 1, 1)),
                    token, 3);
            fail("expected ResumeTokenException mixing dfs/sorted");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testCorruptTokenStringFailsClosed() {
        RTree<String, Point> tree = gridTree(3, 3);
        ResumeToken token = tree.searchPage(rectangle(0, 0, 2, 2), 2).nextResumeToken().get();
        String encoded = token.encode();
        String corrupted = encoded.substring(0, encoded.length() - 2)
                + (encoded.endsWith("A") ? "B" : "A")
                + encoded.substring(encoded.length() - 1);
        try {
            ResumeToken.parse(corrupted);
            fail("expected ResumeTokenException for corrupt token");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testGarbageTokenFailsClosed() {
        try {
            ResumeToken.parse("not-a-token!!!");
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
        try {
            ResumeToken.parse((String) null);
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
        try {
            ResumeToken.parse(new byte[] { 1, 2, 3 });
            fail("expected ResumeTokenException");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testTruncatedTokenFailsClosed() {
        RTree<String, Point> tree = gridTree(3, 3);
        byte[] bytes = tree.searchPage(rectangle(0, 0, 2, 2), 1).nextResumeToken().get()
                .toBytes();
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        try {
            ResumeToken.parse(truncated);
            fail("expected ResumeTokenException for truncated token");
        } catch (ResumeTokenException e) {
            assertDiagnostic(e);
        }
    }

    @Test
    public void testInvalidPageSizeRejected() {
        RTree<String, Point> tree = gridTree(2, 2);
        try {
            tree.searchPage(rectangle(0, 0, 1, 1), 0);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("pageSize"));
        }
    }

    private static void assertDiagnostic(ResumeTokenException e) {
        assertNotNull(e.getMessage());
        assertTrue("diagnostic message should be non empty", e.getMessage().length() > 10);
    }

    // ---------------------------------------------------------------
    // duplicate geometries, R*-split and FlatBuffers round-trip
    // ---------------------------------------------------------------

    @Test
    public void testDuplicateGeometriesPagingPreservesAllAndOrder() {
        RTree<String, Point> tree = RTree.maxChildren(3).<String, Point>create()
                .add(Entries.entry("a", point(2, 2)))
                .add(Entries.entry("b", point(2, 2)))
                .add(Entries.entry("c", point(2, 2)))
                .add(Entries.entry("d", point(2, 2)))
                .add(Entries.entry("e", point(2, 2)))
                .add(Entries.entry("f", point(8, 8)));
        Rectangle window = rectangle(1, 1, 3, 3);
        List<Entry<String, Point>> oneShot = tree.search(window).toList().toBlocking().single();
        assertEquals(5, oneShot.size());
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>intersects(window), oneShot);
    }

    @Test
    public void testDeepRStarSplitPagingEqualsOneShot() {
        // 300 points through the R* split path with small capacity so several
        // levels and reinsertions occur
        RTree<String, Point> tree = randomTree(300, 2024L, true);
        assertTrue(tree.calculateDepth() >= 2);
        Rectangle window = rectangle(4, 4, 15, 16);
        List<Entry<String, Point>> oneShot = tree.search(window).toList().toBlocking().single();
        assertTrue(oneShot.size() > 20);
        assertPagingEqualsOneShot(tree, SearchQuery.<String, Point>intersects(window), oneShot);
    }

    @Test
    public void testStructureVersionStableAcrossFlatBuffersRoundTrip() throws IOException {
        RTree<String, Point> tree = randomTree(120, 99L, true);
        RTree<String, Point> restored = roundTripFlatBuffers(tree);
        assertEquals(tree.structureVersion(), restored.structureVersion());
    }

    @Test
    public void testPagingEqualsOneShotAfterFlatBuffersRoundTrip() throws IOException {
        RTree<String, Point> tree = randomTree(140, 101L, false);
        RTree<String, Point> restored = roundTripFlatBuffers(tree);
        Rectangle window = rectangle(3, 3, 13, 14);
        List<Entry<String, Point>> oneShot = tree.search(window).toList().toBlocking().single();
        assertPagingEqualsOneShot(restored, SearchQuery.<String, Point>intersects(window),
                oneShot);
    }

    @Test
    public void testTokenResumesAcrossFlatBuffersRoundTrip() throws IOException {
        RTree<String, Point> tree = randomTree(140, 202L, true);
        RTree<String, Point> restored = roundTripFlatBuffers(tree);
        Rectangle window = rectangle(2, 3, 14, 16);
        // page 1 and 2 come from the original tree, later pages resume on the
        // restored (but structurally identical) tree, token passed as an
        // encoded string to simulate a different process
        Page<String, Point> page1 = tree.searchPage(window, 5);
        String encoded1 = page1.nextResumeToken().get().encode();
        Page<String, Point> page2 = restored.searchPage(window, 5,
                ResumeToken.parse(encoded1));
        String encoded2 = page2.nextResumeToken().get().encode();
        List<Entry<String, Point>> collected = new ArrayList<Entry<String, Point>>();
        collected.addAll(page1.entries());
        collected.addAll(page2.entries());
        ResumeToken token = ResumeToken.parse(encoded2);
        RTree<String, Point> current = restored;
        while (token != null) {
            Page<String, Point> page = current.searchPage(window, 7, token);
            collected.addAll(page.entries());
            token = page.nextResumeToken().orElse(null);
        }
        List<Entry<String, Point>> oneShot = tree.search(window).toList().toBlocking().single();
        assertEquals(oneShot, collected);
    }

    @Test
    public void testSortedPagingResumesAcrossFlatBuffersRoundTrip() throws IOException {
        RTree<String, Point> tree = randomTree(120, 303L, false);
        RTree<String, Point> restored = roundTripFlatBuffers(tree);
        Rectangle anchor = rectangle(5, 5, 6, 6);
        Sort<String, Point> sort = Sort.ascendingDistance(anchor);
        SearchQuery<String, Point> q = SearchQuery.<String, Point>all();
        Page<String, Point> page1 = tree.searchPage(q, sort, null, 4);
        String encoded = page1.nextResumeToken().get().encode();
        List<Entry<String, Point>> collected = new ArrayList<Entry<String, Point>>(
                page1.entries());
        ResumeToken token = ResumeToken.parse(encoded);
        while (token != null) {
            Page<String, Point> page = restored.searchPage(q, sort, token, 6);
            collected.addAll(page.entries());
            token = page.nextResumeToken().orElse(null);
        }
        List<Entry<String, Point>> expected = new ArrayList<Entry<String, Point>>(
                tree.entries().toList().toBlocking().single());
        java.util.Collections.sort(expected,
                com.github.davidmoten.rtree.internal.Comparators
                        .<String, Point>ascendingDistance(anchor));
        assertEquals(expected, collected);
    }

    @Test
    public void testValueChangeChangesStructureVersion() throws IOException {
        RTree<String, Point> tree = gridTree(3, 3);
        long v1 = tree.structureVersion();
        // same geometry, different value at same logical position
        RTree<String, Point> rebuilt = RTree.maxChildren(4).<String, Point>create();
        int n = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                String value = (i == 1 && j == 1) ? "changed" : "p" + n;
                rebuilt = rebuilt.add(Entries.entry(value, point(i, j)));
                n++;
            }
        }
        assertTrue(v1 != rebuilt.structureVersion());
    }

    @Test
    public void testTokenStringRoundTripsThroughBytes() {
        RTree<String, Point> tree = randomTree(50, 404L, false);
        ResumeToken token = tree.searchPage(rectangle(0, 0, 5, 5), 3).nextResumeToken().get();
        // URL-safe: no padding, no + or /
        String encoded = token.encode();
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("="));
        ResumeToken parsed = ResumeToken.parse(encoded);
        assertEquals(encoded, parsed.encode());
        ResumeToken fromBytes = ResumeToken.parse(token.toBytes());
        assertEquals(encoded, fromBytes.encode());
    }
}
