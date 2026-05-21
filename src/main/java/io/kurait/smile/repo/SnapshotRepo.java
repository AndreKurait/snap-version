package io.kurait.smile.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kurait.smile.s3.S3Store;
import io.kurait.smile.s3.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Knows the on-disk layout of an Elasticsearch / OpenSearch snapshot repository
 * (post-7.x layout — the "index_metadata_lookup" + "index_metadata_identifiers" form).
 *
 * <ul>
 *   <li>{@code index.latest}                                                — 8-byte BE generation N
 *   <li>{@code index-N}                                                    — JSON manifest
 *   <li>{@code meta-<snap-uuid>.dat}                                       — global metadata (Smile + codec)
 *   <li>{@code snap-<snap-uuid>.dat}                                       — snapshot info     (Smile + codec)
 *   <li>{@code indices/<index-uuid>/meta-<id>.dat}                         — index metadata    (Smile + codec)
 *   <li>{@code indices/<index-uuid>/<shard-N>/snap-<snap-uuid>.dat}        — shard metadata    (Smile + codec)
 * </ul>
 *
 * <p>This mirrors {@code SnapshotFileFinder_ES_7_10} +
 * {@code BaseSnapshotFileFinder} from opensearch-migrations.
 */
public class SnapshotRepo {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepo.class);

    public static final String INDEX_LATEST = "index.latest";
    public static final Pattern INDEX_GEN_PATTERN = Pattern.compile("^index-(\\d+)$");
    public static final String INDICES_PREFIX = "indices/";

    public static final String CODEC_GLOBAL_METADATA = "metadata";
    public static final String CODEC_INDEX_METADATA  = "index-metadata";
    public static final String CODEC_SNAPSHOT        = "snapshot";

    private final SnapshotStore store;
    private final ObjectMapper json = new ObjectMapper();

    public SnapshotRepo(SnapshotStore store) {
        this.store = store;
    }

    /* ---------------- Repo manifest ---------------- */

    /** Read {@code index.latest} (8-byte BE long). Returns -1 if missing. */
    public long readIndexLatest() {
        try {
            byte[] bytes = store.get(INDEX_LATEST);
            if (bytes.length < 8) return -1;
            return ByteBuffer.wrap(bytes).getLong();
        } catch (Exception e) {
            log.debug("index.latest not present: {}", e.toString());
            return -1;
        }
    }

    /** Find the highest {@code index-N} that exists in the repo root (fallback if index.latest absent). */
    public long findHighestIndexGeneration() {
        return store.listFilesAtLevel("").stream()
                .map(INDEX_GEN_PATTERN::matcher)
                .filter(Matcher::matches)
                .map(m -> Long.parseLong(m.group(1)))
                .max(Comparator.naturalOrder())
                .orElse(-1L);
    }

    /** Read the JSON manifest {@code index-N}, returning the parsed tree. */
    public JsonNode readManifest(long generation) throws IOException {
        return json.readTree(store.get("index-" + generation));
    }

    /* ---------------- File-name helpers ---------------- */

    public String globalMetaKey(String snapshotUuid) {
        return "meta-" + snapshotUuid + ".dat";
    }

    public String snapshotInfoKey(String snapshotUuid) {
        return "snap-" + snapshotUuid + ".dat";
    }

    public String indexMetadataKey(String indexUuid, String indexFileId) {
        return INDICES_PREFIX + indexUuid + "/meta-" + indexFileId + ".dat";
    }

    public String shardMetadataKey(String snapshotUuid, String indexUuid, int shardId) {
        return INDICES_PREFIX + indexUuid + "/" + shardId + "/snap-" + snapshotUuid + ".dat";
    }

    /* ---------------- High-level enumeration ---------------- */

    /** Snapshot reference parsed from {@code index-N}. */
    public record SnapshotRef(String name, String uuid, String version) {}

    /** Index reference parsed from {@code index-N}. */
    public record IndexRef(String name, String uuid) {}

    public List<SnapshotRef> listSnapshots(JsonNode manifest) {
        List<SnapshotRef> out = new ArrayList<>();
        JsonNode arr = manifest.path("snapshots");
        if (arr.isArray()) {
            arr.forEach(n -> out.add(new SnapshotRef(
                    n.path("name").asText(null),
                    n.path("uuid").asText(null),
                    n.path("version").asText(null))));
        }
        return out;
    }

    public List<IndexRef> listIndices(JsonNode manifest) {
        List<IndexRef> out = new ArrayList<>();
        JsonNode obj = manifest.path("indices");
        obj.fieldNames().forEachRemaining(name -> {
            JsonNode entry = obj.get(name);
            out.add(new IndexRef(name, entry.path("id").asText(null)));
        });
        return out;
    }

    /** Returns the {@code index_metadata_identifiers} key for (snapshot, index). */
    public String resolveIndexMetadataFileId(JsonNode manifest, String snapshotName, String indexName) {
        // 1. find the snapshot's index_metadata_lookup
        JsonNode snaps = manifest.path("snapshots");
        if (!snaps.isArray()) return null;
        String indexUuid = null;
        JsonNode indices = manifest.path("indices");
        if (indices.isObject() && indices.has(indexName)) {
            indexUuid = indices.get(indexName).path("id").asText(null);
        }
        if (indexUuid == null) return null;

        for (JsonNode snap : snaps) {
            if (snapshotName.equals(snap.path("name").asText())) {
                JsonNode lookup = snap.path("index_metadata_lookup");
                if (lookup.has(indexUuid)) {
                    String key = lookup.get(indexUuid).asText();
                    JsonNode ids = manifest.path("index_metadata_identifiers");
                    if (ids.has(key)) {
                        return ids.get(key).asText();
                    }
                    return key; // fallback: some older ES wrote the id directly
                }
            }
        }
        return null;
    }

    public SnapshotStore getStore() {
        return store;
    }
}
