package com.github.davidmoten.rtree;

/**
 * Thrown when a resume token cannot be used to continue a paged spatial
 * query. Cursors fail closed: a token is only accepted when it is well
 * formed, passes its integrity check, was issued against an identical tree
 * structure version, and is resumed with the same query and sort
 * parameters. Every mismatch raises this exception with diagnostic
 * context; no partial page is returned.
 */
public final class ResumeTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructor.
     *
     * @param message
     *            diagnostic description of why the token was rejected
     */
    public ResumeTokenException(String message) {
        super(message);
    }

    /**
     * Constructor.
     *
     * @param message
     *            diagnostic description of why the token was rejected
     * @param cause
     *            underlying failure encountered while decoding the token
     */
    public ResumeTokenException(String message, Throwable cause) {
        super(message, cause);
    }

}
