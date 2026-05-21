package io.kurait.smile.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import io.kurait.smile.repo.SnapshotRepo;
import io.kurait.smile.version.VersionCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds a minimal but byte-faithful snapshot repo on disk for tests:
 * <ul>
 *   <li>{@code index.latest} — 8-byte BE long with the current generation</li>
 *   <li>{@code index-N}      — plain JSON manifest with {@code snapshots[].version}</li>
 *   <li>{@code snap-<uuid>.dat} — Smile + Lucene CodecUtil frame, codec="snapshot"</li>
 * </ul>
 *
 * <p>Doesn't build shard files / index metadata since {@code VersionRewriter}
 * doesn't read them. Used by both unit tests and CLI integration tests.
 */
public final class FakeRepo {

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeRepo() {}

    /**
     * Initialise an empty directory as a one-snapshot repo at the given version.
     */
    public static void build(Path root, String snapshotName, String uuid, String version) throws IOException {
        // index.latest
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(0L);
        Files.write(root.resolve("index.latest"), buf.array());

        // index-0
        ObjectNode manifest = JSON.createObjectNode();
        ArrayNode snaps = manifest.putArray("snapshots");
        snaps.add(makeSnapshotNode(snapshotName, uuid, version));
        manifest.putObject("indices");
        manifest.putObject("index_metadata_identifiers");
        Files.write(root.resolve("index-0"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));

        // snap-<uuid>.dat
        writeSnapDat(root, uuid, version);
    }

    /**
     * Append a second (or later) snapshot to an existing repo.
     */
    public static void appendSnapshot(Path root, String snapshotName, String uuid, String version, long newGen) throws IOException {
        byte[] cur = Files.readAllBytes(root.resolve("index-0"));
        ObjectNode manifest = (ObjectNode) JSON.readTree(cur);
        ((ArrayNode) manifest.withArray("snapshots")).add(makeSnapshotNode(snapshotName, uuid, version));
        Files.write(root.resolve("index-" + newGen),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.putLong(newGen);
        Files.write(root.resolve("index.latest"), buf.array());
        writeSnapDat(root, uuid, version);
    }

    private static ObjectNode makeSnapshotNode(String name, String uuid, String version) {
        ObjectNode snap = JSON.createObjectNode();
        snap.put("name", name);
        snap.put("uuid", uuid);
        snap.put("state", 1);
        snap.put("version", version);
        snap.putObject("index_metadata_lookup");
        return snap;
    }

    private static void writeSnapDat(Path root, String uuid, String version) throws IOException {
        int versionId = VersionCodec.parse(version).toOpenSearchId();
        ObjectNode tree = JSON.createObjectNode();
        ObjectNode inner = tree.putObject("snapshot");
        inner.put("name", "fake");
        inner.put("uuid", uuid);
        inner.put("version_id", versionId);
        inner.put("state", "SUCCESS");
        inner.putArray("indices");
        inner.put("start_time", 0L);
        inner.put("end_time", 0L);
        inner.put("total_shards", 0);
        inner.put("successful_shards", 0);
        inner.putArray("failures");

        byte[] smile = SmileJson.treeToSmile(tree);
        byte[] framed = MetadataCodec.encode(smile, SnapshotRepo.CODEC_SNAPSHOT, 1, false);
        Files.write(root.resolve("snap-" + uuid + ".dat"), framed);
    }
}
