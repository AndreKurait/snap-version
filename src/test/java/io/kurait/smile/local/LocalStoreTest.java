package io.kurait.smile.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalStoreTest {

    @Test
    void roundTrip(@TempDir Path tmp) {
        LocalStore s = new LocalStore(tmp);
        s.put("foo.txt", "hello".getBytes());
        assertThat(new String(s.get("foo.txt"))).isEqualTo("hello");
        // overwrite
        s.put("foo.txt", "world".getBytes());
        assertThat(new String(s.get("foo.txt"))).isEqualTo("world");
    }

    @Test
    void listsRecursively(@TempDir Path tmp) throws IOException {
        LocalStore s = new LocalStore(tmp);
        s.put("index-0", "manifest".getBytes());
        s.put("indices/abc/meta-1.dat", "metaA".getBytes());
        s.put("indices/abc/0/snap-x.dat", "shardA".getBytes());

        List<String> all = s.list("indices/");
        assertThat(all).containsExactlyInAnyOrder(
                "indices/abc/meta-1.dat",
                "indices/abc/0/snap-x.dat");

        List<String> level = s.listFilesAtLevel("");
        assertThat(level).containsExactly("index-0");
    }

    @Test
    void rejectsPathTraversal(@TempDir Path tmp) {
        LocalStore s = new LocalStore(tmp);
        assertThatThrownBy(() -> s.get("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.put("/abs/path", new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void describeIncludesRoot(@TempDir Path tmp) {
        LocalStore s = new LocalStore(tmp);
        assertThat(s.describe()).startsWith("local:").contains(tmp.toAbsolutePath().normalize().toString());
    }

    @Test
    void rejectsSymlinkedFilesAndDirectories(@TempDir Path tmp) throws IOException {
        Path repo = Files.createDirectory(tmp.resolve("repo"));
        Path outside = Files.createDirectory(tmp.resolve("outside"));
        Path secret = Files.writeString(outside.resolve("metadata"), "untouched");
        Files.createSymbolicLink(repo.resolve("linked-dir"), outside);
        Files.createSymbolicLink(repo.resolve("linked-file"), secret);
        LocalStore store = new LocalStore(repo);

        assertThatThrownBy(() -> store.get("linked-file")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get("linked-dir/metadata")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("linked-dir/new.dat", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("linked-file", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list("linked-dir")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.listFilesAtLevel("linked-dir")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.list("")).isEmpty();
        assertThat(store.listFilesAtLevel("")).isEmpty();
        assertThat(Files.readString(secret)).isEqualTo("untouched");
        assertThat(outside.resolve("new.dat")).doesNotExist();
    }

    @Test
    void failedAtomicReplacementRemovesTemporaryFile(@TempDir Path tmp) throws IOException {
        Path target = Files.createDirectory(tmp.resolve("target"));
        Files.writeString(target.resolve("existing"), "keep");
        LocalStore store = new LocalStore(tmp);

        assertThatThrownBy(() -> store.put("target", new byte[]{1})).isInstanceOf(RuntimeException.class);

        try (var entries = Files.list(tmp)) {
            assertThat(entries.toList()).containsExactly(target);
        }
        assertThat(Files.readString(target.resolve("existing"))).isEqualTo("keep");
    }

    @Test
    void rejectsEmptyAndRootKeys(@TempDir Path tmp) {
        LocalStore store = new LocalStore(tmp);
        assertThatThrownBy(() -> store.put("", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put(".", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
    }
}
