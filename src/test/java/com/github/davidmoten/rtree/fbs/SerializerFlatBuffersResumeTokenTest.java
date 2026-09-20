package com.github.davidmoten.rtree.fbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.InternalStructure;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.ResumeToken;
import com.github.davidmoten.rtree.SearchPage;
import com.github.davidmoten.rtree.Serializer;
import com.github.davidmoten.rtree.Serializers;
import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Point;
import com.github.davidmoten.rtree.geometry.Rectangle;

/**
 * Verifies that a {@link ResumeToken} issued against an R-tree remains valid
 * after the tree is serialized to FlatBuffers and deserialized again (the
 * 'resume from another process' scenario), for both internal structures.
 */
public class SerializerFlatBuffersResumeTokenTest {

    private static final Rectangle REGION = Geometries.rectangle(2.5, 1.5, 15.5, 8.5);
    private static final int PAGE_SIZE = 7;

    @Test
    public void testResumeTokenSurvivesRoundTripDefaultStructure() throws IOException {
        roundTrip(InternalStructure.DEFAULT);
    }

    @Test
    public void testResumeTokenSurvivesRoundTripSingleArrayStructure() throws IOException {
        roundTrip(InternalStructure.SINGLE_ARRAY);
    }

    private static void roundTrip(InternalStructure structure) throws IOException {
        RTree<String, Point> tree = RTree.star().maxChildren(4).create();
        for (int i = 0; i < 200; i++) {
            tree = tree.add("v" + i, Geometries.point(i % 20, i / 20));
        }
        SearchPage<String, Point> first = tree.searchPage(REGION, PAGE_SIZE);
        assertTrue(first.hasNext());
        ResumeToken token = first.nextToken().get();

        Serializer<String, Point> serializer = Serializers.flatBuffers().utf8();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        serializer.write(tree, out);
        RTree<String, Point> tree2 = serializer.read(
                new ByteArrayInputStream(out.toByteArray()), out.size(), structure);

        // the structural version is content-derived so it survives the
        // round-trip unchanged
        assertEquals(tree.structuralVersion(), tree2.structuralVersion());

        // resume the search on the deserialized tree using the token issued
        // against the original tree
        List<Entry<String, Point>> drained = new ArrayList<Entry<String, Point>>(first.entries());
        SearchPage<String, Point> page = tree2.searchPage(REGION, token);
        drained.addAll(page.entries());
        while (page.hasNext()) {
            page = tree2.searchPage(REGION, page.nextToken().get());
            drained.addAll(page.entries());
        }

        List<String> actual = toValues(drained);
        List<String> expected = toValues(tree.search(REGION).toList().toBlocking().single());
        assertEquals(expected, actual);
    }

    private static List<String> toValues(List<Entry<String, Point>> entries) {
        List<String> list = new ArrayList<String>();
        for (Entry<String, Point> entry : entries) {
            list.add(entry.value());
        }
        return list;
    }
}
