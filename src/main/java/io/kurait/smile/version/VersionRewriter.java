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
import io.kurait.smile.s3.S3Store;
import io.kurait.smile.s3.SnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
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
        ObjectNode indexN = (ObjectNode) json.readTree(indexNBytes);

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

    /**
     * Apply the rewrite plan: mutates index-N + each selected snap-*.dat in place on S3.
     * Returns a list of human-readable change descriptions.
     */
    public List<String> apply(Inventory inv, RewritePlan plan) throws IOException {
        List<String> changes = new ArrayList<>();

        // --- Patch index-N ---
        ObjectNode indexN = inv.indexNTree();
        ArrayNode snaps = indexN.withArray("snapshots");
        for (JsonNode node : snaps) {
            ObjectNode snap = (ObjectNode) node;
            String uuid = snap.path("uuid").asText(null);
            boolean selected = plan.targets().stream().anyMatch(e -> uuid != null && uuid.equals(e.uuid()));
            if (!selected) continue;

            // version (string)
            if (snap.has("version")) {
                String oldVer = snap.get("version").asText();
                if (!plan.targetVersion().asString().equals(oldVer)) {
                    snap.put("version", plan.targetVersion().asString());
                    changes.add(String.format("[%s] %s.snapshots[uuid=%s].version: %s -> %s",
                            inv.indexNKey(), inv.indexNKey(), uuid, oldVer, plan.targetVersion().asString()));
                }
            }
            // version_id (int) -- present in newer index-N formats. Use the same flavor
            // the snapshot was originally written in so the cluster sees a consistent value.
            if (snap.has("version_id")) {
                long oldId = snap.get("version_id").asLong();
                VersionCodec.Flavor flavor = entryFor(plan.targets(), uuid).detectedFlavor();
                long newId = plan.targetVersion().toId(flavor);
                if (oldId != newId) {
                    snap.put("version_id", newId);
                    changes.add(String.format("[%s] %s.snapshots[uuid=%s].version_id: %d -> %d",
                            inv.indexNKey(), inv.indexNKey(), uuid, oldId, newId));
                }
            }
        }
        byte[] newIndexN = jsonBytesPretty(indexN);
        store.put(inv.indexNKey(), newIndexN);

        // --- Patch each selected snap-<uuid>.dat ---
        for (SnapshotEntry e : plan.targets()) {
            if (e.snapDatKey() == null) {
                changes.add("[skip] no snap-" + e.uuid() + ".dat in repo (orphan?)");
                continue;
            }
            byte[] raw = store.get(e.snapDatKey());
            MetadataCodec.Decoded decoded = MetadataCodec.decode(raw, SnapshotRepo.CODEC_SNAPSHOT);
            JsonNode tree = SmileJson.smileToTree(decoded.smileBytes());
            ObjectNode snapshot = (ObjectNode) tree.path("snapshot");
            if (snapshot.isMissingNode() || snapshot.isNull()) {
                changes.add("[skip] " + e.snapDatKey() + " missing 'snapshot' object");
                continue;
            }
            int oldId = snapshot.path("version_id").asInt(0);
            int newId = plan.targetVersion().toId(e.detectedFlavor());
            if (oldId == newId) continue;
            snapshot.put("version_id", newId);

            byte[] newSmile = SmileJson.treeToSmile(tree);
            byte[] reframed = MetadataCodec.encode(newSmile, decoded);
            store.put(e.snapDatKey(), reframed);

            changes.add(String.format("[%s] snapshot.version_id: %d -> %d (%s -> %s)",
                    e.snapDatKey(), oldId, newId,
                    safeFormat(oldId), plan.targetVersion().asString()));
        }
        return changes;
    }

    private static SnapshotEntry entryFor(List<SnapshotEntry> targets, String uuid) {
        return targets.stream()
                .filter(e -> uuid != null && uuid.equals(e.uuid()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no entry for uuid " + uuid));
    }

    private static String safeFormat(int id) {
        try { return VersionCodec.fromAnyId(id).asString(); } catch (Exception ex) { return "?"; }
    }

    private byte[] jsonBytesPretty(JsonNode tree) throws JsonProcessingException {
        return json.writeValueAsBytes(tree);
    }
}
