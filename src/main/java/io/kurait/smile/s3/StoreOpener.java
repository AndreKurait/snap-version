package io.kurait.smile.s3;

import io.kurait.smile.local.LocalStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Single entry point that picks {@link S3Store} or {@link LocalStore} based on the
 * shape of the path the user gave us.
 */
public final class StoreOpener {

    private StoreOpener() {}

    /**
     * Open a snapshot repo store given either an {@code s3://...} URI or a local directory path.
     * S3-only options (region, endpoint, creds, profile, pathStyle) are ignored when the path
     * resolves to a local directory.
     */
    public static SnapshotStore open(String pathOrUri,
                                     String region,
                                     String endpoint,
                                     String accessKey,
                                     String secretKey,
                                     boolean pathStyle,
                                     String profile) {
        if (pathOrUri == null || pathOrUri.isEmpty()) {
            throw new IllegalArgumentException("repo path/URI is required");
        }
        if (pathOrUri.startsWith("s3://")) {
            return S3Store.open(pathOrUri, region, endpoint, accessKey, secretKey, pathStyle, profile);
        }

        // Treat anything else as a filesystem path. Allow ~/ expansion.
        String expanded = expandTilde(pathOrUri);
        Path p = Paths.get(expanded).toAbsolutePath().normalize();
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException(
                    "not an s3:// URI and not an existing directory: " + pathOrUri);
        }
        return new LocalStore(p);
    }

    private static String expandTilde(String s) {
        if (s.startsWith("~/") || s.equals("~")) {
            String home = System.getProperty("user.home");
            return s.length() == 1 ? home : home + s.substring(1);
        }
        return s;
    }
}
