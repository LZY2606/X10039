package com.github.davidmoten.rtree;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.github.davidmoten.guavamini.Preconditions;
import com.github.davidmoten.rtree.geometry.Geometry;

/**
 * One immutable page of a paged spatial query. If {@link #nextResumeToken()}
 * is present the query has more results and another call to
 * {@code RTree.searchPage} with that token returns the next page. When it is
 * absent the traversal is complete.
 */
public final class Page<T, S extends Geometry> {

    private final List<Entry<T, S>> entries;
    private final ResumeToken nextResumeToken;

    Page(List<Entry<T, S>> entries, ResumeToken nextResumeToken) {
        Preconditions.checkNotNull(entries);
        this.entries = Collections.unmodifiableList(entries);
        this.nextResumeToken = nextResumeToken;
    }

    /**
     * Matched entries in this page in query order.
     *
     * @return page entries (never null)
     */
    public List<Entry<T, S>> entries() {
        return entries;
    }

    /**
     * Returns the token that resumes the query after this page, or
     * {@link Optional#empty()} when the traversal is exhausted.
     *
     * @return next resume token if present
     */
    public Optional<ResumeToken> nextResumeToken() {
        return Optional.ofNullable(nextResumeToken);
    }

    /**
     * Returns true if another page exists.
     *
     * @return whether more results remain
     */
    public boolean hasNext() {
        return nextResumeToken != null;
    }

    @Override
    public String toString() {
        return "Page [entries=" + entries.size() + ", hasNext=" + hasNext() + "]";
    }

}
