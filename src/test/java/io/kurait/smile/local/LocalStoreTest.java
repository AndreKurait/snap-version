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
}
