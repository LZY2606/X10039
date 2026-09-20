package com.github.davidmoten.rtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.internal.util.ImmutableStack;

import rx.functions.Func1;

/**
 * Depth-first paged search engine. The traversal is a line-by-line port of
 * the stack discipline in {@link Backpressure} so that, for a given query,
 * the concatenation of all pages is exactly the sequence produced by
 * {@code RTree.search(...)} (the natural, stable order). The only difference
 * is that the continuation is returned to the caller as a serializable
 * {@link CursorPath} rather than held in a {@link rx.Subscriber}.
 */
final class CursorSearch {

    private CursorSearch() {
        // prevent instantiation
    }

    static final class PageResult<T, S extends Geometry> {
        final List<Entry<T, S>> entries;
        final CursorPath path;

        PageResult(List<Entry<T, S>> entries, CursorPath path) {
            this.entries = Collections.unmodifiableList(entries);
            this.path = path;
        }
    }

    /**
     * Runs one page of the search from the given continuation path.
     *
     * @param root
     *            root node
     * @param query
     *            query being paged
     * @param start
     *            continuation from the previous page, or {@code null} /
     *            empty path for the first page
     * @param pageSize
     *            maximum number of matched entries in the returned page
     * @return page entries and the continuation path (empty when exhausted)
     */
    static <T, S extends Geometry> PageResult<T, S> search(Node<T, S> root,
            SearchQuery<T, S> query, CursorPath start, int pageSize) {
        ImmutableStack<NodePosition<T, S>> stack = toStack(root, start);
        Func1<? super Geometry, Boolean> condition = query.nodeCondition();
        Func1<? super Entry<T, S>, Boolean> filter = query.entryFilter();
        List<Entry<T, S>> result = new ArrayList<Entry<T, S>>(Math.min(pageSize, 64));
        long remaining = pageSize;

        while (!stack.isEmpty() && remaining > 0) {
            NodePosition<T, S> np = stack.peek();
            Node<T, S> node = np.node();
            int position = np.position();
            if (position == node.count()) {
                stack = afterLastInNode(stack);
            } else if (node instanceof NonLeaf) {
                Node<T, S> child = ((NonLeaf<T, S>) node).child(position);
                if (condition.call(child.geometry())) {
                    stack = stack.push(new NodePosition<T, S>(child, 0));
                } else {
                    stack = stack.pop().push(np.nextPosition());
                }
            } else {
                Entry<T, S> entry = ((Leaf<T, S>) node).entry(position);
                boolean match = condition.call(entry.geometry());
                if (match && filter != null) {
                    match = Boolean.TRUE.equals(filter.call(entry));
                }
                stack = stack.pop().push(np.nextPosition());
                if (match) {
                    result.add(entry);
                    remaining--;
                }
            }
        }
        return new PageResult<T, S>(result, CursorPath.fromStack(stack));
    }

    /**
     * Returns true if at least one more matched entry exists from the given
     * continuation. Used to decide whether a page that did not fill its
     * budget has actually exhausted the query.
     */
    static <T, S extends Geometry> boolean hasNext(Node<T, S> root, SearchQuery<T, S> query,
            CursorPath start) {
        PageResult<T, S> probeResult = search(root, query, start, 1);
        return !probeResult.entries.isEmpty();
    }

    /**
     * Rebuilds the live node stack from a serializable continuation path.
     */
    @SuppressWarnings("unchecked")
    static <T, S extends Geometry> ImmutableStack<NodePosition<T, S>> toStack(Node<T, S> root,
            CursorPath start) {
        if (start == null || start.isEmpty()) {
            return ImmutableStack.create(new NodePosition<T, S>(root, 0));
        }
        List<CursorPath.Frame> frames = start.frames();
        CursorPath.Frame rootFrame = frames.get(0);
        if (rootFrame.childIndex() != -1) {
            throw new ResumeTokenException("resume path root frame must have childIndex -1 but was "
                    + rootFrame.childIndex());
        }
        if (rootFrame.position() < 0 || rootFrame.position() > root.count()) {
            throw new ResumeTokenException(
                    "resume path root position out of range: position=" + rootFrame.position()
                            + ", nodeCount=" + root.count());
        }
        ImmutableStack<NodePosition<T, S>> stack = ImmutableStack
                .create(new NodePosition<T, S>(root, rootFrame.position()));
        Node<T, S> current = root;
        for (int i = 1; i < frames.size(); i++) {
            if (!(current instanceof NonLeaf)) {
                throw new ResumeTokenException(
                        "resume path descends through a non internal node at frame " + (i - 1)
                                + " (node count=" + current.count() + ")");
            }
            NonLeaf<T, S> nonLeaf = (NonLeaf<T, S>) current;
            CursorPath.Frame frame = frames.get(i);
            int childIndex = frame.childIndex();
            if (childIndex < 0 || childIndex >= nonLeaf.count()) {
                throw new ResumeTokenException(
                        "resume path child index out of range at frame " + i + ": childIndex="
                                + childIndex + ", childCount=" + nonLeaf.count());
            }
            Node<T, S> child = nonLeaf.child(childIndex);
            if (frame.position() < 0 || frame.position() > child.count()) {
                throw new ResumeTokenException(
                        "resume path position out of range at frame " + i + ": position="
                                + frame.position() + ", nodeCount=" + child.count());
            }
            // While this child is active the parent cursor points at the
            // child's own index.
            CursorPath.Frame parentFrame = frames.get(i - 1);
            if (parentFrame.position() != childIndex) {
                throw new ResumeTokenException("resume path is inconsistent at frame " + i
                        + ": parent position " + parentFrame.position()
                        + " does not match child index " + childIndex);
            }
            stack = stack.push(new NodePosition<T, S>(child, frame.position()));
            current = child;
        }
        return stack;
    }

    private static <T, S extends Geometry> ImmutableStack<NodePosition<T, S>> afterLastInNode(
            ImmutableStack<NodePosition<T, S>> stack) {
        ImmutableStack<NodePosition<T, S>> popped = stack.pop();
        if (popped.isEmpty()) {
            return popped;
        }
        NodePosition<T, S> previous = popped.peek();
        return popped.pop().push(previous.nextPosition());
    }

    /**
     * Computes the 0-based depth-first ordinal of every matched entry. Used
     * as the total-order tie breaker for distance sorted paging.
     */
    static <T, S extends Geometry> List<Entry<T, S>> allInDfsOrder(Node<T, S> root,
            SearchQuery<T, S> query) {
        PageResult<T, S> page = search(root, query, null, Integer.MAX_VALUE);
        return new ArrayList<Entry<T, S>>(page.entries);
    }

    static <T, S extends Geometry> List<Entry<T, S>> sortByDistance(
            List<Entry<T, S>> entries, com.github.davidmoten.rtree.geometry.Rectangle anchor) {
        // stable sort by ascending distance; input is in DFS order so equal
        // distances retain DFS order
        List<Entry<T, S>> copy = new ArrayList<Entry<T, S>>(entries);
        Collections.sort(copy,
                com.github.davidmoten.rtree.internal.Comparators.<T, S>ascendingDistance(anchor));
        return copy;
    }
}
