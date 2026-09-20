package com.github.davidmoten.rtree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.github.davidmoten.rtree.geometry.Geometry;

/**
 * One page of results from a paged (resumable) search of an {@link RTree}. If
 * {@link #nextToken()} is present the token can be used to fetch the next page
 * from the same tree (or from another tree with identical structure and
 * content, for instance after a serialization round-trip or in another
 * process).
 *
 * @param <T>
 *            the entry value type
 * @param <S>
 *            the entry geometry type
 */
public final class SearchPage<T, S extends Geometry> {

    private final List<Entry<T, S>> entries;
    private final Optional<ResumeToken> nextToken;

    SearchPage(List<Entry<T, S>> entries, Optional<ResumeToken> nextToken) {
        this.entries = Collections.unmodifiableList(new ArrayList<Entry<T, S>>(entries));
        this.nextToken = nextToken;
    }

    /**
     * Returns the entries of this page in traversal order.
     *
     * @return entries of this page
     */
    public List<Entry<T, S>> entries() {
        return entries;
    }

    /**
     * Returns the token that can be presented to
     * {@link RTree#searchPage(com.github.davidmoten.rtree.geometry.Rectangle, ResumeToken)}
     * to obtain the next page, or empty if the search is exhausted.
     *
     * @return token for the next page if any
     */
    public Optional<ResumeToken> nextToken() {
        return nextToken;
    }

    /**
     * Returns true if and only if more pages are available after this one.
     *
     * @return whether more pages are available
     */
    public boolean hasNext() {
        return nextToken.isPresent();
    }

    @Override
    public String toString() {
        return "SearchPage [entries=" + entries.size() + ", hasNext=" + hasNext() + "]";
    }
}
