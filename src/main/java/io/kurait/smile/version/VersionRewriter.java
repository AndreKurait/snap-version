package io.kurait.smile.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kurait.smile.codec.MetadataCodec;
import io.kurait.smile.codec.SmileJson;
import io.kurait.smile.repo.SnapshotRepo;
import io.kurait.smile.s3.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers all snapshots in a repo, reads each snapshot's recorded version,
 * and (if asked) rewrites the version to a target value across every place it lives:
 *
 * <ol>
 *   <li>{@code index-N} (plain JSON manifest): {@code snapshots[*].version} string,
 *       and {@code snapshots[*].version_id} int when present.</li>
 *   <li>{@code snap-<uuid>.dat} (Smile + codec): {@code snapshot.version_id} int.</li>
 * </ol>
 *
 * <p>The shard-level snap files under {@code indices/<id>/<shard>/} do not carry
 * version metadata and are left untouched.
 */
public class VersionRewriter {

    private static final Logger log = LoggerFactory.getLogger(VersionRewriter.class);
    private static final Pattern SNAP_FILE = Pattern.compile("^snap-([A-Za-z0-9_\\-]+)\\.dat$");

    private final SnapshotStore store;
    private final SnapshotRepo repo;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public VersionRewriter(SnapshotStore store) {
        this.store = store;
        this.repo = new SnapshotRepo(store);
    }

    /* ----------------------------- DISCOVERY ----------------------------- */

    /** What the inventory step found. */
    public record Inventory(
            long generation,
            String indexNKey,                 // e.g. "index-0"
            ObjectNode indexNTree,            // mutable copy of index-N
            List<SnapshotEntry> snapshots
    ) {}

    /** One row in the snapshot table. */
    public record SnapshotEntry(
            String name,
            String uuid,
            String snapDatKey,                // root-level "snap-<uuid>.dat"
            String versionStringFromIndexN,   // may be null
            Integer versionIdFromSnapDat,     // may be null
            VersionCodec.Parsed parsedVersion, // best-effort decode; may be null on disagreement
            VersionCodec.Flavor detectedFlavor // OPENSEARCH (mask bit 27) or ELASTICSEARCH; null if no version_id
    ) {}

    /**
     * Scan the repo and return everything we'd need to print a status table or rewrite versions.
     * Does not modify anything.
     */
    public Inventory inventory() throws IOException {
        long gen = repo.readIndexLatest();
        if (gen < 0) gen = repo.findHighestIndexGeneration();
        if (gen < 0) throw new IOException("no index-N file found in repo " + store.describe());
        String indexNKey = "index-" + gen;

        byte[] indexNBytes = store.get(indexNKey);
        JsonNode parsedIndex = json.readTree(indexNBytes);
        if (!(parsedIndex instanceof ObjectNode indexN) || !indexN.path("snapshots").isArray()) {
            throw new IOException(indexNKey + " must contain an object with a snapshots array");
        }

        // Build a quick lookup of root-level snap-*.dat files
        List<String> rootFiles = store.listFilesAtLevel("");
        Map<String, String> snapFilesByUuid = new LinkedHashMap<>();
        for (String name : rootFiles) {
            Matcher m = SNAP_FILE.matcher(name);
            if (m.matches()) {
                snapFilesByUuid.put(m.group(1), name);
            }
        }

        List<SnapshotEntry> entries = new ArrayList<>();
        ArrayNode snaps = indexN.withArray("snapshots");
        for (JsonNode snap : snaps) {
            String name = snap.path("name").asText(null);
            String uuid = snap.path("uuid").asText(null);
            String versionString = snap.has("version") && !snap.get("version").isNull() ?
                    snap.get("version").asText() : null;

            String snapDatKey = snapFilesByUuid.get(uuid);
            Integer versionId = null;
            VersionCodec.Parsed parsed = null;
            VersionCodec.Flavor flavor = null;
            if (snapDatKey != null) {
                try {
                    byte[] raw = store.get(snapDatKey);
                    MetadataCodec.Decoded d = MetadataCodec.decode(raw, SnapshotRepo.CODEC_SNAPSHOT);
                    JsonNode tree = SmileJson.smileToTree(d.smileBytes());
                    JsonNode vid = tree.path("snapshot").path("version_id");
                    if (vid.isInt() || vid.isLong()) {
                        versionId = vid.asInt();
                        flavor = VersionCodec.detectFlavor(versionId);
                        try {
                            parsed = VersionCodec.fromAnyId(versionId);
                        } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    log.warn("Failed to decode {}: {}", snapDatKey, e.toString());
                }
            }

            // Cross-check with index-N's version string
            if (parsed == null && versionString != null) {
                try { parsed = VersionCodec.parse(versionString); } catch (Exception ignored) {}
            }
            // Default flavor when no version_id was present: assume OpenSearch
            // (modern OS+ES post-7.x repos always carry version_id; legacy ES1-6
            // may not, but those use a different repo format anyway).
            if (flavor == null) flavor = VersionCodec.Flavor.OPENSEARCH;

            entries.add(new SnapshotEntry(name, uuid, snapDatKey, versionString, versionId, parsed, flavor));
        }

        return new Inventory(gen, indexNKey, indexN, entries);
    }

    /* ----------------------------- REWRITE ----------------------------- */

    public record RewritePlan(
            VersionCodec.Parsed targetVersion,
            List<SnapshotEntry> targets
    ) {}

    /** Compute the full rewrite plan for one or more snapshot UUIDs. */
    public RewritePlan plan(VersionCodec.Parsed target, List<SnapshotEntry> selected) {
        return new RewritePlan(target, selected);
    }

    private record PreparedRewrite(
            Map<String, byte[]> originals,
            Map<String, byte[]> replacements,
            List<String> changes
    ) {}

    /** Validate and describe a rewrite without writing any objects. */
    public List<String> preview(Inventory inv, RewritePlan plan) throws IOException {
        return prepare(inv, plan).changes();
    }

    /**
     * Validate every selected blob before writing, then replace snapshot blobs and
     * finally the manifest. This is not a transaction across repository objects:
     * callers must operate on an offline copy and keep a backup for write failures.
     */
    public List<String> apply(Inventory inv, RewritePlan plan) throws IOException {
        PreparedRewrite prepared = prepare(inv, plan);
        if (prepared.replacements().isEmpty()) return prepared.changes();

        // Detect changes since preflight. This is a guard, not a repository lock;
        // external writers must remain stopped for the entire operation.
        for (var entry : prepared.originals().entrySet()) {
            if (!Arrays.equals(entry.getValue(), store.get(entry.getKey()))) {
                throw new IOException("repository changed during preflight: " + entry.getKey());
            }
        }

        List<String> written = new ArrayList<>();
        try {
            for (var entry : prepared.replacements().entrySet()) {
                store.put(entry.getKey(), entry.getValue());
                written.add(entry.getKey());
            }
        } catch (RuntimeException e) {
            throw new IOException("rewrite failed; completed writes: " + written
                    + ". The failed write may also have completed. Restore the offline copy from backup before retrying.", e);
        }
        return prepared.changes();
    }

    private PreparedRewrite prepare(Inventory inv, RewritePlan plan) throws IOException {
        if (plan.targetVersion() == null) throw new IllegalArgumentException("target version is required");
        Map<String, SnapshotEntry> selected = new LinkedHashMap<>();
        for (SnapshotEntry entry : plan.targets()) {
            if (entry.uuid() == null || selected.putIfAbsent(entry.uuid(), entry) != null) {
                throw new IOException("selected snapshots must have distinct, non-null UUIDs");
            }
            if (entry.snapDatKey() == null) {
                throw new IOException("missing snapshot metadata for " + entry.uuid() + "; nothing was written");
            }
            if (!entry.snapDatKey().equals("snap-" + entry.uuid() + ".dat")
                    || !SNAP_FILE.matcher(entry.snapDatKey()).matches()) {
                throw new IOException("invalid root snapshot key: " + entry.snapDatKey());
            }
        }

        long currentGeneration = repo.readIndexLatest();
        if (currentGeneration < 0) currentGeneration = repo.findHighestIndexGeneration();
        if (currentGeneration != inv.generation()) {
            throw new IOException("repository generation changed since inventory; reload before rewriting");
        }
        byte[] indexBytes = store.get(inv.indexNKey());
        if (!inv.indexNTree().equals(json.readTree(indexBytes))) {
            throw new IOException("manifest changed since inventory; reload before rewriting");
        }
        ObjectNode indexN = inv.indexNTree().deepCopy();
        ArrayNode snapshots = (ArrayNode) indexN.get("snapshots");
        Map<String, ObjectNode> manifestEntries = new LinkedHashMap<>();
        for (JsonNode node : snapshots) {
            if (!(node instanceof ObjectNode object) || !object.path("uuid").isTextual()) {
                throw new IOException("manifest snapshot entries must be objects with a UUID");
            }
            String uuid = object.get("uuid").asText();
            if (manifestEntries.putIfAbsent(uuid, object) != null) {
                throw new IOException("duplicate snapshot UUID in manifest: " + uuid);
            }
        }

        Map<String, byte[]> originals = new LinkedHashMap<>();
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        List<String> manifestChanges = new ArrayList<>();
        List<String> blobChanges = new ArrayList<>();
        originals.put(inv.indexNKey(), indexBytes);

        for (SnapshotEntry entry : selected.values()) {
            ObjectNode manifestEntry = manifestEntries.get(entry.uuid());
            if (manifestEntry == null) throw new IOException("snapshot is not in the manifest: " + entry.uuid());
            byte[] raw = store.get(entry.snapDatKey());
            originals.put(entry.snapDatKey(), raw);
            MetadataCodec.Decoded decoded = MetadataCodec.decode(raw, SnapshotRepo.CODEC_SNAPSHOT);
            if (decoded.version() != 1) {
                throw new IOException("unsupported snapshot codec version: " + decoded.version());
            }
            JsonNode tree = SmileJson.smileToTree(decoded.smileBytes());
            if (tree == null || !(tree.path("snapshot") instanceof ObjectNode snapshot)) {
                throw new IOException(entry.snapDatKey() + " is missing its snapshot object");
            }
            JsonNode versionId = snapshot.path("version_id");
            if (!versionId.isIntegralNumber() || !versionId.canConvertToInt()) {
                throw new IOException(entry.snapDatKey() + " needs a 32-bit integer version_id");
            }
            int oldId = versionId.intValue();
            VersionCodec.Parsed oldVersion;
            try {
                oldVersion = VersionCodec.fromAnyId(oldId);
            } catch (IllegalArgumentException e) {
                throw new IOException("unsupported version_id in " + entry.snapDatKey(), e);
            }
            if (entry.versionIdFromSnapDat() == null || oldId != entry.versionIdFromSnapDat()) {
                throw new IOException(entry.snapDatKey() + " changed or was unreadable at inventory time; reload before rewriting");
            }
            if (snapshot.has("uuid") && !entry.uuid().equals(snapshot.path("uuid").asText())) {
                throw new IOException("snapshot UUID mismatch in " + entry.snapDatKey());
            }
            VersionCodec.Flavor flavor = VersionCodec.detectFlavor(oldId);
            int newId = plan.targetVersion().toId(flavor);

            if (manifestEntry.has("version")) {
                JsonNode oldVersionNode = manifestEntry.get("version");
                if (!oldVersionNode.isTextual() || !oldVersion.asString().equals(oldVersionNode.asText())) {
                    throw new IOException("manifest and snapshot version disagree for " + entry.uuid());
                }
                if (!plan.targetVersion().asString().equals(oldVersionNode.asText())) {
                    manifestEntry.put("version", plan.targetVersion().asString());
                    manifestChanges.add(String.format("[%s] snapshots[uuid=%s].version: %s -> %s",
                            inv.indexNKey(), entry.uuid(), oldVersionNode.asText(), plan.targetVersion().asString()));
                }
            }
            if (manifestEntry.has("version_id")) {
                JsonNode oldManifestId = manifestEntry.get("version_id");
                if (!oldManifestId.isIntegralNumber() || !oldManifestId.canConvertToInt()
                        || oldManifestId.intValue() != oldId) {
                    throw new IOException("manifest and snapshot version_id disagree for " + entry.uuid());
                }
                if (oldId != newId) {
                    manifestEntry.put("version_id", newId);
                    manifestChanges.add(String.format("[%s] snapshots[uuid=%s].version_id: %d -> %d",
                            inv.indexNKey(), entry.uuid(), oldId, newId));
                }
            }
            if (oldId != newId) {
                snapshot.put("version_id", newId);
                byte[] reframed = MetadataCodec.encode(SmileJson.treeToSmile(tree), decoded);
                replacements.put(entry.snapDatKey(), reframed);
                blobChanges.add(String.format("[%s] snapshot.version_id: %d -> %d (%s -> %s)",
                        entry.snapDatKey(), oldId, newId, safeFormat(oldId), plan.targetVersion().asString()));
            }
        }
        // The manifest is written last, only after every selected blob validates.
        // A no-op preserves the original bytes, including JSON formatting.
        if (!manifestChanges.isEmpty()) replacements.put(inv.indexNKey(), jsonBytesPretty(indexN));
        List<String> changes = new ArrayList<>(manifestChanges);
        changes.addAll(blobChanges);
        return new PreparedRewrite(originals, replacements, List.copyOf(changes));
    }

    private static String safeFormat(int id) {
        try { return VersionCodec.fromAnyId(id).asString(); } catch (Exception ex) { return "?"; }
    }

    private byte[] jsonBytesPretty(JsonNode tree) throws JsonProcessingException {
        return json.writeValueAsBytes(tree);
    }
}
