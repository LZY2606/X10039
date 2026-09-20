package com.github.davidmoten.rtree;

/**
 * Thrown when a {@link ResumeToken} cannot be honoured, for example when the
 * token is presented against a tree whose structure has changed, when the
 * search predicate differs from the one the token was created with, or when
 * the traversal path recorded in the token is not valid for the tree. The
 * message always carries the conflicting values to aid diagnosis.
 */
public final class ResumeTokenException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public ResumeTokenException(String message) {
        super(message);
    }

    public ResumeTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
