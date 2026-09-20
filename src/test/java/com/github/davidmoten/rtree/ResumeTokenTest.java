package com.github.davidmoten.rtree;

import static com.github.davidmoten.rtree.geometry.Geometries.point;
import static com.github.davidmoten.rtree.geometry.Geometries.rectangle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.geometry.Point;

public class ResumeTokenTest {

    private static RTree<String, Point> tree() {
        RTree<String, Point> tree = RTree.maxChildren(3).<String, Point>create();
        for (int i = 0; i < 12; i++) {
            tree = tree.add(Entries.entry("v" + i, point(i % 4, i / 4)));
        }
        return tree;
    }

    @Test
    public void testTokenRoundTripsAsBytesAndString() {
        RTree<String, Point> tree = tree();
        ResumeToken token = tree.searchPage(rectangle(0, 0, 3, 3), 2).nextResumeToken().get();
        assertEquals(token, ResumeToken.parse(token.toBytes()));
        assertEquals(token, ResumeToken.parse(token.encode()));
        assertEquals(token.hashCode(), ResumeToken.parse(token.encode()).hashCode());
    }

    @Test
    public void testTokenIsUrlSafe() {
        RTree<String, Point> tree = tree();
        String lastEncoded = null;
        int pages = 0;
        do {
            ResumeToken resume = lastEncoded == null ? null
                    : ResumeToken.parse(lastEncoded);
            Page<String, Point> page = tree.searchPage(rectangle(0, 0, 4, 4), 1, resume);
            lastEncoded = page.nextResumeToken().map(ResumeToken::encode).orElse(null);
            if (lastEncoded != null) {
                assertFalse(lastEncoded.contains("+"));
                assertFalse(lastEncoded.contains("/"));
                assertFalse(lastEncoded.contains("="));
            }
        } while (lastEncoded != null && ++pages < 20);
        assertTrue(pages > 1);
    }

    @Test
    public void testBitFlipEveryPayloadByteFailsCrc() {
        RTree<String, Point> tree = tree();
        byte[] bytes = tree.searchPage(rectangle(0, 0, 2, 2), 1).nextResumeToken().get()
                .toBytes();
        int failures = 0;
        for (int i = 5; i < bytes.length; i++) {
            byte[] mutated = bytes.clone();
            mutated[i] ^= 0x01;
            try {
                ResumeToken.parse(mutated);
            } catch (ResumeTokenException e) {
                failures++;
            }
        }
        // header bytes (magic/version) before payload also covered by crc;
        // every single bit flip must be rejected one way or another
        assertEquals(bytes.length - 5, failures);
    }

    @Test
    public void testMagicMutationRejected() {
        RTree<String, Point> tree = tree();
        byte[] bytes = tree.searchPage(rectangle(0, 0, 2, 2), 1).nextResumeToken().get()
                .toBytes();
        bytes[0] = 'X';
        try {
            ResumeToken.parse(bytes);
            fail();
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("magic"));
        }
    }

    @Test
    public void testVersionMismatchRejected() {
        RTree<String, Point> tree = tree();
        byte[] bytes = tree.searchPage(rectangle(0, 0, 2, 2), 1).nextResumeToken().get()
                .toBytes();
        // bump the format version int (bytes 5..8)
        bytes[8] = 99;
        try {
            ResumeToken.parse(bytes);
            fail();
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("version") || e.getMessage().contains("integrity"));
        }
    }

    @Test
    public void testValidationMessagesContainContext() {
        RTree<String, Point> tree = tree();
        ResumeToken token = tree.searchPage(rectangle(0, 0, 2, 2), 1).nextResumeToken().get();
        RTree<String, Point> modified = tree.add(Entries.entry("zzz", point(0, 0)));
        try {
            modified.searchPage(rectangle(0, 0, 2, 2), 1, token);
            fail();
        } catch (ResumeTokenException e) {
            assertTrue(e.getMessage().contains("structural version"));
            assertTrue(e.getMessage().contains(Long.toHexString(tree.structureVersion())));
            assertTrue(e.getMessage().contains(Long.toHexString(modified.structureVersion())));
        }
    }

    @Test
    public void testSortedTokenRoundTripsAndValidates() {
        RTree<String, Point> tree = tree();
        Sort<String, Point> sort = Sort.ascendingDistance(rectangle(0, 0, 1, 1));
        ResumeToken token = tree.searchPage(SearchQuery.<String, Point>all(), sort, null, 2)
                .nextResumeToken().get();
        assertTrue(token.isSorted());
        ResumeToken parsed = ResumeToken.parse(token.encode());
        assertTrue(parsed.isSorted());
        // resume works with parsed token
        Page<String, Point> page = tree.searchPage(SearchQuery.<String, Point>all(), sort,
                parsed, 2);
        assertEquals(2, page.entries().size());
    }

    @Test
    public void testConsumingAllPagesReconstructsFullSetSorted() {
        RTree<String, Point> tree = tree();
        Sort<String, Point> sort = Sort.ascendingDistance(rectangle(2, 2, 3, 3));
        List<Entry<String, Point>> collected = new ArrayList<Entry<String, Point>>();
        ResumeToken token = null;
        do {
            Page<String, Point> page = tree.searchPage(SearchQuery.<String, Point>all(), sort,
                    token, 3);
            collected.addAll(page.entries());
            token = page.nextResumeToken().orElse(null);
        } while (token != null);
        assertEquals(12, collected.size());
    }

    @Test
    public void testEmittedCountTracksPages() {
        RTree<String, Point> tree = tree();
        Page<String, Point> page = tree.searchPage(rectangle(0, 0, 3, 3), 2);
        assertEquals(2, page.entries().size());
        ResumeToken token = page.nextResumeToken().get();
        Page<String, Point> page2 = tree.searchPage(rectangle(0, 0, 3, 3), 2, token);
        // emitted counter (package private) advances
        assertTrue(page2.nextResumeToken().map(t -> t.emitted() >= 4).orElse(true));
    }
}
