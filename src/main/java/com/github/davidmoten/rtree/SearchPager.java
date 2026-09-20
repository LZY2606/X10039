package com.github.davidmoten.rtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.github.davidmoten.rtree.geometry.Geometry;
import com.github.davidmoten.rtree.geometry.Rectangle;
import com.github.davidmoten.rtree.internal.util.ImmutableStack;

import rx.functions.Func1;

/**
 * Engine behind the paged search methods of {@link RTree}. Reuses exactly the
 * same depth-first traversal discipline as {@link Backpressure} so that the
 * concatenation of pages is item-for-item identical to a one-shot search.
 */
final class SearchPager {

    private SearchPager() {
        // prevent instantiation
    }

    static String predicateFingerprint(Rectangle r) {
        return "intersects:" + r.x1() + "," + r.y1() + "," + r.x2() + "," + r.y2();
    }

    static <T, S extends Geometry> SearchPage<T, S> firstPage(RTree<T, S> tree, Rectangle r,
            int pageSize) {
        if (!tree.root().isPresent()) {
            return new SearchPage<T, S>(Collections.<Entry<T, S>>emptyList(),
                    Optional.<ResumeToken>empty());
        }
        Func1<? super Geometry, Boolean> condition = RTree.intersects(r);
        ImmutableStack<NodePosition<T, S>> stack = ImmutableStack
                .create(new NodePosition<T, S>(tree.root().get(), 0));
        List<Entry<T, S>> entries = new ArrayList<Entry<T, S>>();
        stack = collect(condition, stack, pageSize, entries);
        return createPage(tree, predicateFingerprint(r), pageSize, entries, stack);
    }

    static <T, S extends Geometry> SearchPage<T, S> nextPage(RTree<T, S> tree, Rectangle r,
            ResumeToken token) {
        if (!ResumeToken.ORDER_DFS.equals(token.order())) {
            throw new ResumeTokenException("token order '" + token.order()
                    + "' is not supported by this search, expected '" + ResumeToken.ORDER_DFS
                    + "': " + token);
        }
        String predicate = predicateFingerprint(r);
        if (!token.predicate().equals(predicate)) {
            throw new ResumeTokenException("token predicate '" + token.predicate()
                    + "' does not match the requested predicate '" + predicate + "': " + token);
        }
        long version = tree.structuralVersion();
        if (token.structuralVersion() != version) {
            throw new ResumeTokenException("token is bound to structural version "
                    + token.structuralVersion() + " but the tree has structural version " + version
                    + " (tree size=" + tree.size() + "): the tree has been modified since the"
                    + " token was issued: " + token);
        }
        if (!tree.root().isPresent()) {
            throw new ResumeTokenException(
                    "token presented against an empty tree, cannot resume: " + token);
        }
        ImmutableStack<NodePosition<T, S>> stack = rebuildStack(tree.root().get(), token);
        Func1<? super Geometry, Boolean> condition = RTree.intersects(r);
        List<Entry<T, S>> entries = new ArrayList<Entry<T, S>>();
        stack = collect(condition, stack, token.pageSize(), entries);
        return createPage(tree, predicate, token.pageSize(), entries, stack);
    }

    private static <T, S extends Geometry> SearchPage<T, S> createPage(RTree<T, S> tree,
            String predicate, int pageSize, List<Entry<T, S>> entries,
            ImmutableStack<NodePosition<T, S>> stack) {
        if (stack.isEmpty()) {
            return new SearchPage<T, S>(entries, Optional.<ResumeToken>empty());
        } else {
            ResumeToken token = new ResumeToken(tree.structuralVersion(), predicate,
                    ResumeToken.ORDER_DFS, pageSize, toPath(stack));
            return new SearchPage<T, S>(entries, Optional.of(token));
        }
    }

    private static <T, S extends Geometry> int[] toPath(ImmutableStack<NodePosition<T, S>> stack) {
        List<Integer> positions = new ArrayList<Integer>();
        for (NodePosition<T, S> np : stack) {
            // iteration is from the top of the stack (current node) to the
            // bottom (root)
            positions.add(np.position());
        }
        int[] path = new int[positions.size()];
        for (int i = 0; i < positions.size(); i++) {
            path[i] = positions.get(positions.size() - 1 - i);
        }
        return path;
    }

    private static <T, S extends Geometry> ImmutableStack<NodePosition<T, S>> rebuildStack(
            Node<T, S> root, ResumeToken token) {
        int[] path = token.path();
        List<Node<T, S>> nodes = new ArrayList<Node<T, S>>(path.length);
        nodes.add(root);
        for (int i = 1; i < path.length; i++) {
            Node<T, S> parent = nodes.get(i - 1);
            if (!(parent instanceof NonLeaf)) {
                throw new ResumeTokenException("token path descends through a leaf at depth "
                        + (i - 1) + " (path=" + toString(path) + "): " + token);
            }
            NonLeaf<T, S> nonLeaf = (NonLeaf<T, S>) parent;
            int childIndex = path[i - 1];
            if (childIndex < 0 || childIndex >= nonLeaf.count()) {
                throw new ResumeTokenException("token path child index " + childIndex
                        + " out of bounds at depth " + (i - 1) + ", node has " + nonLeaf.count()
                        + " children (path=" + toString(path) + "): " + token);
            }
            nodes.add(nonLeaf.child(childIndex));
        }
        ImmutableStack<NodePosition<T, S>> stack = ImmutableStack.empty();
        for (int i = 0; i < path.length; i++) {
            Node<T, S> node = nodes.get(i);
            int position = path[i];
            boolean topFrame = i == path.length - 1;
            // a non-top frame position points at the child on the path so must
            // be a valid child index; the top frame position may equal the
            // node count (page boundary exactly at the end of a node)
            if (position < 0 || position > node.count() || (!topFrame && position == node.count())) {
                throw new ResumeTokenException("token path position " + position
                        + " out of bounds at depth " + i + ", node has " + node.count()
                        + " children (path=" + toString(path) + "): " + token);
            }
            stack = stack.push(new NodePosition<T, S>(node, position));
        }
        return stack;
    }

    private static String toString(int[] path) {
        StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < path.length; i++) {
            if (i > 0) {
                s.append(", ");
            }
            s.append(path[i]);
        }
        return s.append("]").toString();
    }

    /**
     * Traverses from the given stack emitting up to {@code pageSize} entries
     * into {@code out}. Mirrors {@link Backpressure} stack handling exactly so
     * that ordering matches the one-shot search.
     */
    private static <T, S extends Geometry> ImmutableStack<NodePosition<T, S>> collect(
            Func1<? super Geometry, Boolean> condition, ImmutableStack<NodePosition<T, S>> stack,
            int pageSize, List<Entry<T, S>> out) {
        while (!stack.isEmpty() && out.size() < pageSize) {
            NodePosition<T, S> np = stack.peek();
            if (np.position() == np.node().count()) {
                // handle after last in node
                stack = afterLastInNode(stack);
            } else if (np.node() instanceof NonLeaf) {
                // handle non-leaf
                Node<T, S> child = ((NonLeaf<T, S>) np.node()).child(np.position());
                if (condition.call(child.geometry())) {
                    stack = stack.push(new NodePosition<T, S>(child, 0));
                } else {
                    stack = stack.pop().push(np.nextPosition());
                }
            } else {
                // handle leaf
                Entry<T, S> entry = ((Leaf<T, S>) np.node()).entry(np.position());
                if (condition.call(entry.geometry())) {
                    out.add(entry);
                }
                stack = stack.pop().push(np.nextPosition());
            }
        }
        // normalize: collapse exhausted frames exactly as the traversal loop
        // above would do on the next iterations, so that a page boundary
        // falling exactly on the end of the traversal does not issue a
        // superfluous token (which would yield an empty final page)
        while (!stack.isEmpty() && stack.peek().position() == stack.peek().node().count()) {
            stack = afterLastInNode(stack);
        }
        return stack;
    }

    private static <T, S extends Geometry> ImmutableStack<NodePosition<T, S>> afterLastInNode(
            ImmutableStack<NodePosition<T, S>> stack) {
        ImmutableStack<NodePosition<T, S>> stack2 = stack.pop();
        if (stack2.isEmpty()) {
            return stack2;
        } else {
            NodePosition<T, S> previous = stack2.peek();
            return stack2.pop().push(previous.nextPosition());
        }
    }
}
