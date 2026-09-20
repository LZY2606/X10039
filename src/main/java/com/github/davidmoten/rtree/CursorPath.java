package com.github.davidmoten.rtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.internal.util.ImmutableStack;

/**
 * Serializable position within the depth-first traversal of an R-tree.
 *
 * <p>
 * A path is a root-to-leaf list of frames. Each frame records the index of
 * the node among its parent's children ({@code -1} for the root) and the
 * {@code position} cursor inside that node (the index of the next child to
 * visit for an internal node, or the next entry for a leaf). Only the
 * currently active DFS branch is stored; completed branches are popped by the
 * traversal engine exactly as in {@link Backpressure}.
 */
public final class CursorPath {

    /**
     * A single frame in a {@link CursorPath}. Immutable.
     */
    public static final class Frame {
        private final int childIndex;
        private final int position;

        public Frame(int childIndex, int position) {
            this.childIndex = childIndex;
            this.position = position;
        }

        /** Index of this node among its parent's children, or -1 for root. */
        public int childIndex() {
            return childIndex;
        }

        /** Next cursor position within the node. */
        public int position() {
            return position;
        }

        @Override
        public String toString() {
            return "Frame [childIndex=" + childIndex + ", position=" + position + "]";
        }
    }

    private final List<Frame> frames;

    public CursorPath(List<Frame> frames) {
        Preconditions.checkNotNull(frames);
        this.frames = Collections.unmodifiableList(new ArrayList<Frame>(frames));
    }

    /** Root-to-leaf frames. */
    public List<Frame> frames() {
        return frames;
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    @Override
    public String toString() {
        return "CursorPath [frames=" + frames + "]";
    }

    /**
     * Captures the traversal stack produced by the search engine. While a
     * child node is on the stack its parent cursor still points at that
     * child's index (the parent cursor is advanced when the child is popped
     * in {@code afterLastInNode}), so a frame's child index is exactly the
     * position recorded on its parent frame.
     */
    static <T, S extends Geometry> CursorPath fromStack(
            ImmutableStack<NodePosition<T, S>> stack) {
        List<NodePosition<T, S>> topFirst = new ArrayList<NodePosition<T, S>>();
        for (NodePosition<T, S> np : stack) {
            topFirst.add(np);
        }
        // reverse to root-first
        Collections.reverse(topFirst);
        List<Frame> frames = new ArrayList<Frame>(topFirst.size());
        for (int i = 0; i < topFirst.size(); i++) {
            int childIndex;
            if (i == 0) {
                childIndex = -1;
            } else {
                childIndex = topFirst.get(i - 1).position();
            }
            frames.add(new Frame(childIndex, topFirst.get(i).position()));
        }
        return new CursorPath(frames);
    }

}
