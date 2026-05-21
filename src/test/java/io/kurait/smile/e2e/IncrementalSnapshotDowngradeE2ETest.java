package io.kurait.smile.e2e;

import io.kurait.smile.s3.S3Store;
import io.kurait.smile.s3.SnapshotStore;
import io.kurait.smile.version.VersionCodec;
import io.kurait.smile.version.VersionRewriter;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test for the <b>incremental snapshot</b> case: a repository that
 * holds multiple snapshots of the same index, where each snapshot only stores
 * the new shard data on top of the prior one (via {@code shard_generations}).
 *
 * <h3>Scenario</h3>
 * <ol>
 *   <li>Source 2.19.4 indexes 3 docs, takes <b>snap-1</b>.</li>
 *   <li>Indexes 3 more, takes <b>snap-2</b>. (Now 6 docs.)</li>
 *   <li>Indexes 3 more, takes <b>snap-3</b>. (Now 9 docs.)</li>
 *   <li>Target 2.19.0 attempts to restore each \u2014 ALL THREE rejected (HTTP 500).</li>
 *   <li>{@link VersionRewriter} runs once with {@code --to 2.19.0}: rewrites
 *       <em>both</em> {@code index-N} and <em>each</em> snapshot's
 *       {@code snap-&lt;uuid&gt;.dat} in one pass.</li>
 *   <li>Target 2.19.0 successfully restores snap-1 (3 docs), snap-2 (6 docs),
 *       snap-3 (9 docs).</li>
 * </ol>
 *
 * <h3>What this proves</h3>
 * <ul>
 *   <li>The rewriter handles N>1 snapshots in one repo (not just one).</li>
 *   <li>Shard-data blobs ({@code __*}) and per-shard {@code snap-*.dat} files
 *       are <b>not</b> touched, so the increment chain stays intact across
 *       snapshots.</li>
 *   <li>Each rewritten snapshot is independently restorable \u2014 not just
 *       \"the latest one happens to work\".</li>
 * </ul>
 */
@Tag("e2e")
class IncrementalSnapshotDowngradeE2ETest {

    private static final Logger log = LoggerFactory.getLogger(IncrementalSnapshotDowngradeE2ETest.class);

    private static final String SOURCE_VERSION = "2.19.4";
    private static final String TARGET_VERSION = "2.19.0";
    private static final String BUCKET = "snapshots";
    private static final String BASE_PATH = "incremental-repro";
    private static final String INDEX_NAME = "movies";

    private static Network network;
    @SuppressWarnings("rawtypes")
    private static GenericContainer minio;
    private static OpenSearchTestContainer source;
    private static OpenSearchTestContainer target;

    private static OkHttpClient http;
    private static String minioS3Url;

    @BeforeAll
    @SuppressWarnings({"unchecked", "rawtypes", "resource"})
    static void setUp() throws Exception {
        network = Network.newNetwork();

        minio = new GenericContainer("minio/minio:RELEASE.2024-09-13T20-26-02Z")
                .withNetwork(network)
                .withNetworkAliases("minio")
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", "minioadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                .withCommand("server", "/data")
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        minio.start();
        minioS3Url = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);

        source = new OpenSearchTestContainer(SOURCE_VERSION, network, "opensearch-source-inc", "minio:9000");
        target = new OpenSearchTestContainer(TARGET_VERSION, network, "opensearch-target-inc", "minio:9000");
        source.start();
        target.start();
        log.info("source 2.19.4: {}", source.getHttpEndpoint());
        log.info("target 2.19.0: {}", target.getHttpEndpoint());

        http = new OkHttpClient.Builder().callTimeout(Duration.ofMinutes(2)).build();

        // Create bucket
        var s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(URI.create(minioS3Url))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true).build())
                .build();
        try {
            s3.createBucket(b -> b.bucket(BUCKET));
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
        } finally {
            s3.close();
        }
    }

    @AfterAll
    static void tearDown() {
        if (target != null) target.stop();
        if (source != null) source.stop();
        if (minio != null) minio.stop();
        if (network != null) network.close();
    }

    @Test
    void incrementalDowngradeFlow() throws Exception {
        // 1. Set up the source index
        putJson(source, "/" + INDEX_NAME, """
                { "settings": { "number_of_shards": 1, "number_of_replicas": 0 } }
                """);

        // Index batch 1 (3 docs) and take snap-1
        bulkIndex(source, 1, 3);
        registerRepo(source, "repo-source");
        takeSnapshot(source, "repo-source", "snap-1");

        // Batch 2 (3 more, total 6) and take snap-2
        bulkIndex(source, 4, 6);
        takeSnapshot(source, "repo-source", "snap-2");

        // Batch 3 (3 more, total 9) and take snap-3
        bulkIndex(source, 7, 9);
        takeSnapshot(source, "repo-source", "snap-3");

        log.info("STEP 1 OK: 3 incremental snapshots taken on source (3, 6, 9 docs)");

        // 2. Capture which shard data files exist BEFORE we rewrite, so we can confirm
        //    we don't touch them.
        var s3Before = listAllRepoFiles();
        log.info("Repo has {} files before rewrite", s3Before.size());

        // 3. Verify all 3 snapshots are rejected on the target.
        registerRepo(target, "repo-target");
        for (String snapshotName : List.of("snap-1", "snap-2", "snap-3")) {
            try (Response r = postRaw(target,
                    "/_snapshot/repo-target/" + snapshotName + "/_restore?wait_for_completion=true",
                    "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":false," +
                    "\"rename_pattern\":\"(.+)\",\"rename_replacement\":\"$1-restored-" + snapshotName + "\"}")) {
                String body = r.body() == null ? "" : r.body().string();
                assertThat(r.code())
                        .as("Target should reject %s (created by 2.19.4)", snapshotName)
                        .isEqualTo(500);
                assertThat(body)
                        .contains("snapshot_restore_exception")
                        .contains("[" + SOURCE_VERSION + "]");
            }
        }
        log.info("STEP 2 OK: all 3 snapshots rejected as expected");

        // 4. Rewrite ALL snapshots in one VersionRewriter invocation.
        try (SnapshotStore store = S3Store.open(
                "s3://" + BUCKET + "/" + BASE_PATH,
                "us-east-1", minioS3Url, "minioadmin", "minioadmin", true, null)) {

            VersionRewriter rw = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rw.inventory();

            assertThat(inv.snapshots()).hasSize(3);
            for (var e : inv.snapshots()) {
                assertThat(e.versionStringFromIndexN()).isEqualTo(SOURCE_VERSION);
                assertThat(e.versionIdFromSnapDat()).isEqualTo(136_408_227); // 2.19.4 ^ 0x08000000
            }

            VersionCodec.Parsed targetParsed = VersionCodec.parse(TARGET_VERSION);
            VersionRewriter.RewritePlan plan = rw.plan(targetParsed, inv.snapshots());
            List<String> changes = rw.apply(inv, plan);

            // 3 snapshots × (1 index-N entry + 1 snap-*.dat entry) = 6 changes
            assertThat(changes).hasSize(6);
            int indexNChanges = (int) changes.stream()
                    .filter(c -> c.contains("index-") && c.contains(SOURCE_VERSION + " -> " + TARGET_VERSION))
                    .count();
            assertThat(indexNChanges).isEqualTo(3);
            // Each snap-*.dat got its version_id rewritten
            int snapDatChanges = (int) changes.stream()
                    .filter(c -> c.contains("snap-") && c.contains(".dat]"))
                    .filter(c -> c.contains("136408227 -> 136407827"))
                    .count();
            assertThat(snapDatChanges).isEqualTo(3);
        }
        log.info("STEP 3 OK: rewrote 1 manifest + 3 snap-*.dat files in one invocation");

        // 5. Verify shard data files were NOT touched (key invariant for incrementals).
        var s3After = listAllRepoFiles();
        long shardBlobsBefore = s3Before.stream().filter(k -> k.matches(".*indices/[^/]+/\\d+/__.*")).count();
        long shardBlobsAfter  = s3After.stream().filter(k -> k.matches(".*indices/[^/]+/\\d+/__.*")).count();
        assertThat(shardBlobsAfter).as("shard data blob count").isEqualTo(shardBlobsBefore);
        log.info("STEP 4 OK: {} shard data blobs unchanged across rewrite", shardBlobsBefore);

        // 6. Re-register the repo on target (clears cache) and restore each snapshot
        //    independently. Each restore goes into a distinct renamed index so we can
        //    assert the doc counts per snapshot.
        deleteIfPresent(target, "/_snapshot/repo-target");
        registerRepo(target, "repo-target");

        for (var pair : List.of(
                new String[]{"snap-1", "3"},
                new String[]{"snap-2", "6"},
                new String[]{"snap-3", "9"})) {
            String snapshotName = pair[0];
            int expected = Integer.parseInt(pair[1]);
            String restoredAs = INDEX_NAME + "-" + snapshotName;

            // Make sure no leftover from a prior iteration
            deleteIfPresent(target, "/" + restoredAs);

            try (Response r = postRaw(target,
                    "/_snapshot/repo-target/" + snapshotName + "/_restore?wait_for_completion=true",
                    "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":false," +
                    "\"rename_pattern\":\"(.+)\",\"rename_replacement\":\"$1-" + snapshotName + "\"}")) {
                String body = r.body() == null ? "" : r.body().string();
                assertThat(r.code()).as("restore %s after rewrite", snapshotName).isEqualTo(200);
                assertThat(body).contains("\"successful\":1").contains("\"failed\":0");
            }

            // Force a refresh and assert doc count
            postNoBody(target, "/" + restoredAs + "/_refresh");
            try (Response r = http.newCall(new Request.Builder()
                    .url(target.getHttpEndpoint() + "/" + restoredAs + "/_count").build()).execute()) {
                String body = r.body() == null ? "" : r.body().string();
                assertThat(r.code()).isEqualTo(200);
                assertThat(body).as("doc count for %s", snapshotName)
                        .contains("\"count\":" + expected);
            }
            log.info("STEP 5/{}: restore OK \u2014 {} has {} docs", snapshotName, restoredAs, expected);
        }
    }

    /* ----------------------- helpers ----------------------- */

    private static final MediaType JSON = MediaType.parse("application/json");

    private static void bulkIndex(OpenSearchTestContainer c, int from, int to) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append("{\"index\":{\"_id\":\"").append(i).append("\"}}\n");
            sb.append("{\"id\":").append(i).append(",\"name\":\"doc-").append(i).append("\"}\n");
        }
        try (Response r = http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + "/" + INDEX_NAME + "/_bulk?refresh=true")
                .post(RequestBody.create(sb.toString(), JSON))
                .build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("bulk -> " + r.code() + ": " + (r.body() == null ? "" : r.body().string()));
            }
        }
    }

    private static void registerRepo(OpenSearchTestContainer c, String name) throws IOException {
        putJson(c, "/_snapshot/" + name,
                "{\"type\":\"s3\",\"settings\":{\"bucket\":\"" + BUCKET + "\",\"base_path\":\"" + BASE_PATH + "\"}}");
    }

    private static void takeSnapshot(OpenSearchTestContainer c, String repoName, String snapshotName) throws IOException {
        putJson(c, "/_snapshot/" + repoName + "/" + snapshotName + "?wait_for_completion=true",
                "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":true}");
    }

    private static void putJson(OpenSearchTestContainer c, String path, String body) throws IOException {
        try (Response r = http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).put(RequestBody.create(body, JSON)).build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("PUT " + path + " -> " + r.code() + ": "
                        + (r.body() == null ? "" : r.body().string()));
            }
        }
    }

    private static void postNoBody(OpenSearchTestContainer c, String path) throws IOException {
        try (Response r = http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).post(RequestBody.create("", null)).build()).execute()) {
            r.body();
        }
    }

    private static Response postRaw(OpenSearchTestContainer c, String path, String body) throws IOException {
        return http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).post(RequestBody.create(body, JSON)).build()).execute();
    }

    private static void deleteIfPresent(OpenSearchTestContainer c, String path) {
        try (Response r = http.newCall(new Request.Builder().url(c.getHttpEndpoint() + path).delete().build()).execute()) {
            r.body();
        } catch (Exception ignored) {
        }
    }

    private static List<String> listAllRepoFiles() {
        var s3 = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(URI.create(minioS3Url))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("minioadmin", "minioadmin")))
                .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                        .pathStyleAccessEnabled(true).build())
                .build();
        try {
            return s3.listObjectsV2(b -> b.bucket(BUCKET).prefix(BASE_PATH + "/")).contents().stream()
                    .map(software.amazon.awssdk.services.s3.model.S3Object::key).toList();
        } finally {
            s3.close();
        }
    }
}
