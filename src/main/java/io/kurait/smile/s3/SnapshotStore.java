package io.kurait.smile.s3;

import java.io.Closeable;
import java.util.List;

/**
 * Storage abstraction over a snapshot repository.
 *
 * <p>Implementations must support:
 * <ul>
 *   <li>{@link #get} — fetch the bytes of a repo-relative key ("index-0",
 *       "snap-&lt;uuid&gt;.dat", "indices/&lt;uuid&gt;/meta-&lt;id&gt;.dat", etc.)</li>
 *   <li>{@link #put} — write bytes back to that same key (atomic-replace semantics)</li>
 *   <li>{@link #list} — recursive enumeration under a sub-prefix</li>
 *   <li>{@link #listFilesAtLevel} — single-level, returns bare file names</li>
 * </ul>
 *
 * <p>Two implementations: {@link S3Store} (AWS S3 / MinIO) and
 * {@link io.kurait.smile.local.LocalStore} (a directory on the local filesystem).
 */
public interface SnapshotStore extends Closeable {

    /** Bytes of a repo-relative key. Throws if the key doesn't exist. */
    byte[] get(String relativeKey);

    /** Replace the bytes at a repo-relative key. Implementations create parent dirs as needed. */
    void put(String relativeKey, byte[] body);

    /** Recursive list of all keys under {@code subPrefix} (relative to the repo root). */
    List<String> list(String subPrefix);

    /** Single-level list (immediate children only) under {@code subPrefix}, returning bare names. */
    List<String> listFilesAtLevel(String subPrefix);

    /** Human-readable description (used in log lines and the TUI title). */
    String describe();

    @Override
    void close();
}
