package io.kurait.smile.local;

import io.kurait.smile.s3.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@link SnapshotStore} backed by a directory on the local filesystem.
 *
 * <p>The directory is treated as the repo root: keys like {@code "index-0"} or
 * {@code "indices/&lt;uuid&gt;/meta-&lt;id&gt;.dat"} resolve to files relative to it.
 *
 * <p>{@link #put} writes via a temp-file-and-atomic-rename so a crash mid-write
 * doesn't corrupt the on-disk snapshot.
 */
public class LocalStore implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(LocalStore.class);

    private final Path root;

    public LocalStore(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + root);
        }
        this.root = root.toAbsolutePath().normalize();
        log.info("Opened LocalStore: root={}", this.root);
    }

    @Override
    public byte[] get(String relativeKey) {
        Path p = resolveSafe(relativeKey);
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new RuntimeException("failed to read " + p, e);
        }
    }

    @Override
    public void put(String relativeKey, byte[] body) {
        Path p = resolveSafe(relativeKey);
        try {
            Files.createDirectories(p.getParent());
            Path tmp = Files.createTempFile(p.getParent(), p.getFileName().toString() + ".", ".tmp");
            Files.write(tmp, body);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            log.info("PUT {} ({} bytes)", p, body.length);
        } catch (IOException e) {
            throw new RuntimeException("failed to write " + p, e);
        }
    }

    @Override
    public List<String> list(String subPrefix) {
        Path base = subPrefix == null || subPrefix.isEmpty() ? root : resolveSafe(subPrefix);
        if (!Files.exists(base)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(Files::isRegularFile).forEach(p -> {
                Path rel = root.relativize(p);
                out.add(rel.toString().replace(java.io.File.separatorChar, '/'));
            });
        } catch (IOException e) {
            throw new RuntimeException("failed to walk " + base, e);
        }
        return out;
    }

    @Override
    public List<String> listFilesAtLevel(String subPrefix) {
        Path base = subPrefix == null || subPrefix.isEmpty() ? root : resolveSafe(subPrefix);
        if (!Files.isDirectory(base)) return List.of();
        List<String> out = new ArrayList<>();
        try (Stream<Path> stream = Files.list(base)) {
            stream.filter(Files::isRegularFile).forEach(p -> out.add(p.getFileName().toString()));
        } catch (IOException e) {
            throw new RuntimeException("failed to list " + base, e);
        }
        return out;
    }

    @Override
    public String describe() {
        return "local:" + root;
    }

    @Override
    public void close() { /* no resources */ }

    private Path resolveSafe(String relativeKey) {
        if (relativeKey.startsWith("/")) {
            throw new IllegalArgumentException("absolute paths not allowed: " + relativeKey);
        }
        Path resolved = root.resolve(relativeKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path traversal blocked: " + relativeKey);
        }
        return resolved;
    }
}
