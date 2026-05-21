package io.kurait.smile.version;

import io.kurait.smile.local.LocalStore;
import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.testsupport.FakeRepo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests against a synthetic snapshot repo on disk. Each test mimics
 * one of the supported workflows (inventory, rewrite all, rewrite a subset)
 * and serves as runnable documentation for {@link VersionRewriter}.
 */
class VersionRewriterTest {

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
}
