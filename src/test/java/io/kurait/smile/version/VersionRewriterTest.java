package io.kurait.smile.version;

import io.kurait.smile.local.LocalStore;
import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.testsupport.FakeRepo;
import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import io.kurait.smile.repo.SnapshotRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end tests against a synthetic snapshot repo on disk. Each test mimics
 * one of the supported workflows (inventory, rewrite all, rewrite a subset)
 * and serves as runnable documentation for {@link VersionRewriter}.
 */
class VersionRewriterTest {

    @Test
    void corruptLaterSnapshotDoesNotChangeManifestOrEarlierSnapshot(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        FakeRepo.appendSnapshot(tmp, "snap-B", "uuid-B", "2.19.4", 1);
        byte[] beforeManifest = Files.readAllBytes(tmp.resolve("index-1"));
        byte[] beforeFirst = Files.readAllBytes(tmp.resolve("snap-uuid-A.dat"));
        byte[] corrupt = Files.readAllBytes(tmp.resolve("snap-uuid-B.dat"));
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(tmp.resolve("snap-uuid-B.dat"), corrupt);

        try (RecordingStore store = new RecordingStore(new LocalStore(tmp))) {
            VersionRewriter rewriter = new VersionRewriter(store);
            var inventory = rewriter.inventory();
            assertThatThrownBy(() -> rewriter.apply(inventory,
                    rewriter.plan(VersionCodec.parse("2.19.0"), inventory.snapshots())))
                    .isInstanceOf(IOException.class).hasMessageContaining("CRC32");
            assertThat(store.writes).isEmpty();
        }
        assertThat(Files.readAllBytes(tmp.resolve("index-1"))).isEqualTo(beforeManifest);
        assertThat(Files.readAllBytes(tmp.resolve("snap-uuid-A.dat"))).isEqualTo(beforeFirst);
    }

    @Test
    void missingSnapshotMetadataRejectsBeforeAnyWrite(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        Files.delete(tmp.resolve("snap-uuid-A.dat"));
        try (RecordingStore store = new RecordingStore(new LocalStore(tmp))) {
            VersionRewriter rewriter = new VersionRewriter(store);
            var inventory = rewriter.inventory();
            assertThatThrownBy(() -> rewriter.apply(inventory,
                    rewriter.plan(VersionCodec.parse("2.19.0"), inventory.snapshots())))
                    .isInstanceOf(IOException.class).hasMessageContaining("missing snapshot metadata");
            assertThat(store.writes).isEmpty();
        }
    }

    @Test
    void invalidSnapshotShapeRejectsBeforeAnyWrite(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        Files.write(tmp.resolve("snap-uuid-A.dat"),
                MetadataCodec.encode(SmileJson.jsonToSmile("{\"snapshot\":[]}"),
                        SnapshotRepo.CODEC_SNAPSHOT, 1, false));
        try (RecordingStore store = new RecordingStore(new LocalStore(tmp))) {
            VersionRewriter rewriter = new VersionRewriter(store);
            var inventory = rewriter.inventory();
            assertThatThrownBy(() -> rewriter.apply(inventory,
                    rewriter.plan(VersionCodec.parse("2.19.0"), inventory.snapshots())))
                    .isInstanceOf(IOException.class).hasMessageContaining("missing its snapshot object");
            assertThat(store.writes).isEmpty();
        }
    }

    @Test
    void staleInventoryCannotOverwriteChangedManifest(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        try (RecordingStore store = new RecordingStore(new LocalStore(tmp))) {
            VersionRewriter rewriter = new VersionRewriter(store);
            var inventory = rewriter.inventory();
            FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.0");
            assertThatThrownBy(() -> rewriter.apply(inventory,
                    rewriter.plan(VersionCodec.parse("2.15.0"), inventory.snapshots())))
                    .isInstanceOf(IOException.class).hasMessageContaining("manifest changed");
            assertThat(store.writes).isEmpty();
        }
    }

    @Test
    void noOpDoesNotWriteAnyObjects(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.0");
        try (RecordingStore store = new RecordingStore(new LocalStore(tmp))) {
            VersionRewriter rewriter = new VersionRewriter(store);
            var inventory = rewriter.inventory();
            assertThat(rewriter.apply(inventory,
                    rewriter.plan(VersionCodec.parse("2.19.0"), inventory.snapshots()))).isEmpty();
            assertThat(store.writes).isEmpty();
        }
    }

    @Test
    void failedBlobWriteLeavesManifestUnchangedAndReportsCompletedWrites(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        FakeRepo.appendSnapshot(tmp, "snap-B", "uuid-B", "2.19.4", 1);
        byte[] before = Files.readAllBytes(tmp.resolve("index-1"));
        try (RecordingStore store = new RecordingStore(new LocalStore(tmp))) {
            store.failKey = "snap-uuid-B.dat";
            VersionRewriter rewriter = new VersionRewriter(store);
            var inventory = rewriter.inventory();
            assertThatThrownBy(() -> rewriter.apply(inventory,
                    rewriter.plan(VersionCodec.parse("2.19.0"), inventory.snapshots())))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("completed writes: [snap-uuid-A.dat]")
                    .hasMessageContaining("backup");
            assertThat(store.writes).containsExactly("snap-uuid-A.dat");
        }
        assertThat(Files.readAllBytes(tmp.resolve("index-1"))).isEqualTo(before);
    }

    /** Inventory reads version from BOTH the index-N string and the smile-encoded version_id. */
    @Test
    void inventoryReadsVersionFromBothPlaces(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-foo", "uuid-foo", "2.19.4");

        try (SnapshotStore store = new LocalStore(tmp)) {
            VersionRewriter rw = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rw.inventory();

            assertThat(inv.generation()).isEqualTo(0);
            assertThat(inv.indexNKey()).isEqualTo("index-0");
            assertThat(inv.snapshots()).hasSize(1);

            VersionRewriter.SnapshotEntry e = inv.snapshots().get(0);
            assertThat(e.name()).isEqualTo("snap-foo");
            assertThat(e.uuid()).isEqualTo("uuid-foo");
            assertThat(e.versionStringFromIndexN()).isEqualTo("2.19.4");
            assertThat(e.versionIdFromSnapDat()).isEqualTo(136_408_227);
            assertThat(e.parsedVersion().asString()).isEqualTo("2.19.4");
            assertThat(e.snapDatKey()).isEqualTo("snap-uuid-foo.dat");
        }
    }

    /** Rewrite every snapshot in the repo from 2.19.4 to 2.19.0; verify both files. */
    @Test
    void rewriteAllSnapshots(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-foo", "uuid-foo", "2.19.4");

        try (SnapshotStore store = new LocalStore(tmp)) {
            VersionRewriter rw = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rw.inventory();
            VersionCodec.Parsed target = VersionCodec.parse("2.19.0");

            VersionRewriter.RewritePlan plan = rw.plan(target, inv.snapshots());
            List<String> changes = rw.apply(inv, plan);

            assertThat(changes).hasSize(2);
            assertThat(changes.get(0)).contains("index-0").contains("2.19.4 -> 2.19.0");
            assertThat(changes.get(1)).contains("snap-uuid-foo.dat").contains("136408227 -> 136407827");

            VersionRewriter.Inventory after = new VersionRewriter(store).inventory();
            VersionRewriter.SnapshotEntry e = after.snapshots().get(0);
            assertThat(e.versionStringFromIndexN()).isEqualTo("2.19.0");
            assertThat(e.versionIdFromSnapDat()).isEqualTo(136_407_827);
        }
    }

    /** Two snapshots in the repo; rewrite only one by name; verify the other is untouched. */
    @Test
    void rewriteOnlySelectedSnapshots(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-A", "uuid-A", "2.19.4");
        FakeRepo.appendSnapshot(tmp, "snap-B", "uuid-B", "2.19.4", /*newGen=*/ 1);

        try (SnapshotStore store = new LocalStore(tmp)) {
            VersionRewriter rw = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rw.inventory();
            assertThat(inv.snapshots()).extracting(VersionRewriter.SnapshotEntry::name)
                    .containsExactlyInAnyOrder("snap-A", "snap-B");

            List<VersionRewriter.SnapshotEntry> selected = inv.snapshots().stream()
                    .filter(e -> "snap-A".equals(e.name()))
                    .toList();
            VersionRewriter.RewritePlan plan = rw.plan(VersionCodec.parse("2.15.0"), selected);
            rw.apply(inv, plan);

            VersionRewriter.Inventory after = new VersionRewriter(store).inventory();
            for (VersionRewriter.SnapshotEntry e : after.snapshots()) {
                if ("snap-A".equals(e.name())) {
                    assertThat(e.versionStringFromIndexN()).isEqualTo("2.15.0");
                    assertThat(e.versionIdFromSnapDat()).isEqualTo(136_367_827);
                } else {
                    assertThat(e.versionStringFromIndexN()).isEqualTo("2.19.4");
                    assertThat(e.versionIdFromSnapDat()).isEqualTo(136_408_227);
                }
            }
        }
    }

    /** If the repo is already at the target version, snap-*.dat bytes are unchanged. */
    @Test
    void rewriteToSameVersionLeavesSnapDatBytesUnchanged(@TempDir Path tmp) throws Exception {
        FakeRepo.build(tmp, "snap-foo", "uuid-foo", "2.19.0");
        byte[] snapDatBefore = Files.readAllBytes(tmp.resolve("snap-uuid-foo.dat"));

        try (SnapshotStore store = new LocalStore(tmp)) {
            VersionRewriter rw = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rw.inventory();
            VersionRewriter.RewritePlan plan = rw.plan(VersionCodec.parse("2.19.0"), inv.snapshots());
            List<String> changes = rw.apply(inv, plan);

            assertThat(changes).isEmpty();
            byte[] snapDatAfter = Files.readAllBytes(tmp.resolve("snap-uuid-foo.dat"));
            assertThat(snapDatAfter).isEqualTo(snapDatBefore);
        }
    }

    private static class RecordingStore implements SnapshotStore {
        private final SnapshotStore delegate;
        private final List<String> writes = new ArrayList<>();
        private String failKey;

        RecordingStore(SnapshotStore delegate) { this.delegate = delegate; }
        @Override public byte[] get(String key) { return delegate.get(key); }
        @Override public void put(String key, byte[] bytes) {
            if (key.equals(failKey)) throw new IllegalStateException("simulated write failure");
            delegate.put(key, bytes);
            writes.add(key);
        }
        @Override public List<String> list(String prefix) { return delegate.list(prefix); }
        @Override public List<String> listFilesAtLevel(String prefix) { return delegate.listFilesAtLevel(prefix); }
        @Override public String describe() { return delegate.describe(); }
        @Override public void close() { delegate.close(); }
    }
}
