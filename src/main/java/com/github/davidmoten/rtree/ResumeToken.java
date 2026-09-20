package com.github.davidmoten.rtree;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

import com.github.davidmoten.rtree.geometry.Geometry;


/**
 * Opaque, serializable continuation token for a paged spatial query.
 *
 * <p>
 * A token binds five things together:
 * <ol>
 * <li>the tree's {@link StructureVersion structural version} (a content
 * fingerprint of topology, geometry and values),</li>
 * <li>the query id and its full geometry-predicate parameter map,</li>
 * <li>the sort parameters (none, or ascending distance plus the exact
 * anchor),</li>
 * <li>the current traversal position (a depth-first {@link CursorPath}, or an
 * ordinal offset for sorted queries), and</li>
 * <li>a CRC32 integrity check over the payload.</li>
 * </ol>
 *
 * <p>
 * Tokens are exposed as URL-safe Base64 strings ({@link #encode()}) or raw
 * bytes ({@link #toBytes()}). Any decode error, integrity failure, structural
 * mismatch, predicate mismatch, sort mismatch or malformed traversal position
 * raises a {@link ResumeTokenException} with diagnostic context; cursors
 * always fail closed and never silently return a partial or wrong page.
 */
public final class ResumeToken {

    private static final byte[] MAGIC = { 'R', 'T', 'C', 'U', 'R' };
    private static final int FORMAT_VERSION = 1;
    private static final int MODE_DFS = 0;
    private static final int MODE_SORTED = 1;

    private final long structureVersion;
    private final String queryId;
    private final Map<String, String> queryParameters;
    private final String sortToken;
    private final int mode;
    private final CursorPath path;
    private final int offset;
    private final long emitted;

    private ResumeToken(long structureVersion, String queryId,
            Map<String, String> queryParameters, String sortToken, int mode, CursorPath path,
            int offset, long emitted) {
        this.structureVersion = structureVersion;
        this.queryId = queryId;
        this.queryParameters = queryParameters;
        this.sortToken = sortToken;
        this.mode = mode;
        this.path = path;
        this.offset = offset;
        this.emitted = emitted;
    }

    static <T, S extends Geometry> ResumeToken dfs(long structureVersion, SearchQuery<T, S> query,
            Sort<T, S> sort, CursorPath path, long emitted) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("a continuation token requires a non empty path");
        }
        return new ResumeToken(structureVersion, query.id(), new TreeMap<String, String>(
                query.parameters()), sort.toTokenString(), MODE_DFS, path, 0, emitted);
    }

    static <T, S extends Geometry> ResumeToken sorted(long structureVersion, SearchQuery<T, S> query,
            Sort<T, S> sort, int offset, long emitted) {
        if (offset <= 0) {
            throw new IllegalArgumentException("sorted continuation offset must be positive");
        }
        return new ResumeToken(structureVersion, query.id(), new TreeMap<String, String>(
                query.parameters()), sort.toTokenString(), MODE_SORTED, null, offset, emitted);
    }

    /** Tree structural version the token was issued against. */
    public long structureVersion() {
        return structureVersion;
    }

    /** Bound query id. */
    public String queryId() {
        return queryId;
    }

    /** Bound predicate parameters. */
    public Map<String, String> queryParameters() {
        return queryParameters;
    }

    /** Bound sort identity. */
    public String sortToken() {
        return sortToken;
    }

    long emitted() {
        return emitted;
    }

    CursorPath path() {
        return path;
    }

    int offset() {
        return offset;
    }

    boolean isSorted() {
        return mode == MODE_SORTED;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        } else if (!(obj instanceof ResumeToken)) {
            return false;
        } else {
            return java.util.Arrays.equals(toBytes(), ((ResumeToken) obj).toBytes());
        }
    }

    @Override
    public int hashCode() {
        return java.util.Arrays.hashCode(toBytes());
    }

    // ------------------------------------------------------------------
    // Validation (fail closed)
    // ------------------------------------------------------------------

    void validate(long expectedStructureVersion, SearchQuery<?, ?> query, Sort<?, ?> sort) {
        if (structureVersion != expectedStructureVersion) {
            throw new ResumeTokenException("resume token rejected: tree structural version mismatch"
                    + " (token structureVersion=" + Long.toHexString(structureVersion)
                    + ", current structureVersion=" + Long.toHexString(expectedStructureVersion)
                    + "); the tree was modified since the token was issued");
        }
        if (!queryId.equals(query.id())) {
            throw new ResumeTokenException("resume token rejected: query id mismatch"
                    + " (token queryId=" + queryId + ", current queryId=" + query.id() + ")");
        }
        Map<String, String> currentParameters = new TreeMap<String, String>(query.parameters());
        if (!queryParameters.equals(currentParameters)) {
            throw new ResumeTokenException(
                    "resume token rejected: geometry predicate parameters mismatch"
                            + " (token parameters=" + queryParameters + ", current parameters="
                            + currentParameters + ")");
        }
        String currentSort = sort.toTokenString();
        if (!sortToken.equals(currentSort)) {
            throw new ResumeTokenException("resume token rejected: sort parameters mismatch"
                    + " (token sort=" + sortToken + ", current sort=" + currentSort + ")");
        }
    }

    // ------------------------------------------------------------------
    // Serialization
    // ------------------------------------------------------------------

    /**
     * Serializes the token to an opaque URL-safe Base64 string (no line
     * wrapping, no padding).
     *
     * @return encoded token
     */
    public String encode() {
        byte[] bytes = toBytes();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public String toString() {
        return "ResumeToken [structureVersion=" + Long.toHexString(structureVersion)
                + ", queryId=" + queryId + ", sort=" + sortToken + ", mode="
                + (mode == MODE_DFS ? "dfs" : "sorted") + ", emitted=" + emitted + "]";
    }

    /**
     * Serializes the token to raw bytes.
     *
     * @return token bytes
     */
    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            byte[] payload = payloadBytes();
            out.write(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.write(payload);
            out.writeLong(crc(payload));
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ResumeTokenException("failed to serialize resume token", e);
        }
    }

    /**
     * Decodes a token from raw bytes.
     *
     * @param bytes
     *            token bytes produced by {@link #toBytes()}
     * @return parsed token
     * @throws ResumeTokenException
     *             if the token is malformed or fails integrity checks
     */
    public static ResumeToken parse(byte[] bytes) {
        if (bytes == null) {
            throw new ResumeTokenException("resume token cannot be null");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(MAGIC, magic)) {
                throw new ResumeTokenException(
                        "resume token rejected: bad magic header (not an rtree cursor token)");
            }
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new ResumeTokenException("resume token rejected: unsupported format version "
                        + version + " (supported " + FORMAT_VERSION + ")");
            }
            byte[] rest = new byte[bytes.length - MAGIC.length - 4 - 8];
            in.readFully(rest);
            long expectedCrc = in.readLong();
            long actualCrc = crc(rest);
            if (expectedCrc != actualCrc) {
                throw new ResumeTokenException(
                        "resume token rejected: integrity check failed (crc token="
                                + Long.toHexString(expectedCrc) + ", computed="
                                + Long.toHexString(actualCrc) + "); token is corrupt or truncated");
            }
            return readPayload(rest);
        } catch (ResumeTokenException e) {
            throw e;
        } catch (IOException e) {
            throw new ResumeTokenException(
                    "resume token rejected: malformed or truncated token (" + e.getMessage() + ")",
                    e);
        }
    }

    /**
     * Decodes a token from its {@link #encode()} string form.
     *
     * @param encoded
     *            URL-safe Base64 token
     * @return parsed token
     * @throws ResumeTokenException
     *             if the token cannot be decoded or fails integrity checks
     */
    public static ResumeToken parse(String encoded) {
        if (encoded == null) {
            throw new ResumeTokenException("resume token cannot be null");
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            return parse(bytes);
        } catch (IllegalArgumentException e) {
            throw new ResumeTokenException(
                    "resume token rejected: not valid URL-safe Base64 (" + e.getMessage() + ")",
                    e);
        }
    }

    private byte[] payloadBytes() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeLong(structureVersion);
        writeUtf(out, queryId);
        out.writeInt(queryParameters.size());
        for (Map.Entry<String, String> e : queryParameters.entrySet()) {
            writeUtf(out, e.getKey());
            writeUtf(out, e.getValue());
        }
        writeUtf(out, sortToken);
        out.writeByte(mode);
        out.writeLong(emitted);
        if (mode == MODE_DFS) {
            out.writeInt(path.frames().size());
            for (CursorPath.Frame frame : path.frames()) {
                out.writeInt(frame.childIndex());
                out.writeInt(frame.position());
            }
        } else {
            out.writeInt(offset);
        }
        out.flush();
        return bos.toByteArray();
    }

    private static ResumeToken readPayload(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        long structureVersion = in.readLong();
        String queryId = readUtf(in);
        int parameterCount = in.readInt();
        if (parameterCount < 0) {
            throw new ResumeTokenException(
                    "resume token rejected: negative parameter count " + parameterCount);
        }
        TreeMap<String, String> parameters = new TreeMap<String, String>();
        for (int i = 0; i < parameterCount; i++) {
            String key = readUtf(in);
            String value = readUtf(in);
            parameters.put(key, value);
        }
        String sortToken = readUtf(in);
        int mode = in.readByte() & 0xff;
        long emitted = in.readLong();
        if (emitted < 0) {
            throw new ResumeTokenException(
                    "resume token rejected: negative emitted count " + emitted);
        }
        CursorPath path = null;
        int offset = 0;
        if (mode == MODE_DFS) {
            int frameCount = in.readInt();
            if (frameCount <= 0) {
                throw new ResumeTokenException(
                        "resume token rejected: dfs token must carry at least one frame (count="
                                + frameCount + ")");
            }
            java.util.List<CursorPath.Frame> frames =
                    new java.util.ArrayList<CursorPath.Frame>(frameCount);
            for (int i = 0; i < frameCount; i++) {
                int childIndex = in.readInt();
                int position = in.readInt();
                frames.add(new CursorPath.Frame(childIndex, position));
            }
            path = new CursorPath(frames);
        } else if (mode == MODE_SORTED) {
            offset = in.readInt();
            if (offset <= 0) {
                throw new ResumeTokenException(
                        "resume token rejected: sorted token offset must be positive (offset="
                                + offset + ")");
            }
        } else {
            throw new ResumeTokenException(
                    "resume token rejected: unknown traversal mode " + mode);
        }
        if (in.read() != -1) {
            throw new ResumeTokenException(
                    "resume token rejected: trailing bytes after payload");
        }
        return new ResumeToken(structureVersion, queryId, parameters, sortToken, mode, path,
                offset, emitted);
    }

    private static void writeUtf(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            throw new ResumeTokenException("token string fields cannot be null");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Short.MAX_VALUE * 8) {
            throw new ResumeTokenException("token string field too long");
        }
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readUtf(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new ResumeTokenException(
                    "resume token rejected: negative string length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }
}
