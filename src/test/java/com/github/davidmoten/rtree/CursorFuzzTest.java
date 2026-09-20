package com.github.davidmoten.rtree;

import static com.github.davidmoten.rtree.geometry.Geometries.point;
import static com.github.davidmoten.rtree.geometry.Geometries.rectangle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Point;
import com.github.davidmoten.rtree.geometry.Rectangle;

/**
 * Property-style regression for the most dangerous resumable-cursor failure
 * mode: a continuation frame that silently addresses the wrong node after a
 * split/reorder, producing duplicates, gaps or reordered entries that only
 * show up for particular tree shapes and page sizes. The assertion iterates
 * many deterministically generated trees (both quadratic and R* splitters),
 * several query windows and every page size, comparing the concatenated
 * pages to the one-shot observable result item by item.
 */
public class CursorFuzzTest {

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

        int nextInt(int bound) {
            return (int) (Math.floorMod(nextLong(), bound));
        }
    }

    private static RTree<String, Point> build(int count, long seed, boolean star,
            int maxChildren) {
        Rng rng = new Rng(seed);
        RTree<String, Point> tree = star
                ? RTree.star().maxChildren(maxChildren).<String, Point>create()
                : RTree.maxChildren(maxChildren).<String, Point>create();
        for (int i = 0; i < count; i++) {
            int x = rng.nextInt(10);
            int y = rng.nextInt(10);
            tree = tree.add(Entries.entry("i" + i, point(x, y)));
        }
        return tree;
    }

    private static <T, S extends Geometry> List<Entry<T, S>> pageThrough(RTree<T, S> tree,
            SearchQuery<T, S> query, ResumeToken start, int pageSize) {
        List<Entry<T, S>> collected = new ArrayList<Entry<T, S>>();
        ResumeToken token = start;
        int guard = 0;
        do {
            Page<T, S> page = tree.searchPage(query, token, pageSize);
            collected.addAll(page.entries());
            token = page.nextResumeToken().orElse(null);
        } while (token != null && ++guard < 100000);
        assertTrue("paging loop did not terminate", guard < 100000);
        return collected;
    }

    @Test
    public void testDfsPagingFuzzManyShapesAndPageSizes() {
        long seed = 1000L;
        int scenarios = 0;
        for (boolean star : new boolean[] { false, true }) {
            for (int maxChildren : new int[] { 3, 4, 8 }) {
                for (int count : new int[] { 0, 1, 2, 5, 30, 90 }) {
                    RTree<String, Point> tree = build(count, seed += 17, star, maxChildren);
                    for (Rectangle window : new Rectangle[] { rectangle(-1, -1, 0, 0),
                            rectangle(2, 2, 5, 6), rectangle(0, 0, 9, 9),
                            rectangle(4, 4, 4, 4), rectangle(100, 100, 101, 101) }) {
                        SearchQuery<String, Point> query = SearchQuery.intersects(window);
                        List<Entry<String, Point>> expected = tree.search(window).toList()
                                .toBlocking().single();
                        for (int pageSize : new int[] { 1, 2, 3, 7, 1000 }) {
                            List<Entry<String, Point>> actual = pageThrough(tree, query, null,
                                    pageSize);
                            assertEquals("star=" + star + " maxChildren=" + maxChildren
                                    + " count=" + count + " window=" + window + " pageSize="
                                    + pageSize, expected, actual);
                            scenarios++;
                        }
                    }
                }
            }
        }
        assertTrue(scenarios > 100);
    }

    @Test
    public void testSortedPagingFuzzMatchesStableOrder() {
        long seed = 4242L;
        for (boolean star : new boolean[] { false, true }) {
            for (int count : new int[] { 0, 1, 4, 33, 80 }) {
                RTree<String, Point> tree = build(count, seed += 31, star, 4);
                Rectangle anchor = rectangle(3, 3, 4, 4);
                Sort<String, Point> sort = Sort.ascendingDistance(anchor);
                SearchQuery<String, Point> query = SearchQuery.<String, Point>all();
                List<Entry<String, Point>> dfs = tree.entries().toList().toBlocking().single();
                List<Entry<String, Point>> expected = new ArrayList<Entry<String, Point>>(dfs);
                java.util.Collections.sort(expected,
                        com.github.davidmoten.rtree.internal.Comparators
                                .<String, Point>ascendingDistance(anchor));
                for (int pageSize : new int[] { 1, 3, 19 }) {
                    List<Entry<String, Point>> collected = new ArrayList<Entry<String, Point>>();
                    ResumeToken token = null;
                    do {
                        Page<String, Point> page = tree.searchPage(query, sort, token, pageSize);
                        collected.addAll(page.entries());
                        token = page.nextResumeToken().orElse(null);
                    } while (token != null);
                    assertEquals("star=" + star + " count=" + count + " pageSize=" + pageSize,
                            expected, collected);
                }
            }
        }
    }

    @Test
    public void testResumingMidStreamWithDifferentPageSizesStaysCorrect() {
        // page boundaries do not have to align: start with size 2 then
        // continue with size 7 and finally size 1
        RTree<String, Point> tree = build(120, 555L, true, 4);
        Rectangle window = rectangle(1, 1, 8, 8);
        List<Entry<String, Point>> expected = tree.search(window).toList().toBlocking().single();
        List<Entry<String, Point>> collected = new ArrayList<Entry<String, Point>>();
        int[] pageSizes = { 2, 7, 1, 5 };
        ResumeToken token = null;
        int idx = 0;
        do {
            Page<String, Point> page = tree.searchPage(
                    SearchQuery.<String, Point>intersects(window), token,
                    pageSizes[idx % pageSizes.length]);
            collected.addAll(page.entries());
            token = page.nextResumeToken().orElse(null);
            idx++;
        } while (token != null);
        assertEquals(expected, collected);
    }
}
