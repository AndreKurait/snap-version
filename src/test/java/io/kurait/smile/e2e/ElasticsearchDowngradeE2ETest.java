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
 * End-to-end test for the legacy Elasticsearch case.
 *
 * <p>Source ES 7.17.28 takes a snapshot, target ES 7.10.2 rejects it,
 * VersionRewriter rewrites to 7.10.2 in the legacy ES (unmasked) encoding,
 * target ES 7.10.2 then accepts the restore.
 *
 * <p>This test only covers a same-minor patch downgrade (7.17.x → 7.17.y).
 * Cross-minor downgrades like ES 7.17 → 7.10 are NOT supported: ES 7.10's
 * repository parser strict-rejects newer fields (e.g. {@code uuid} on snapshot
 * entries, {@code index_metadata_lookup}/{@code index_metadata_identifiers})
 * that 7.17 always writes. snap-version only rewrites the version metadata;
 * it does not down-translate manifest schemas.
 *
 * <p>This is the test that proves snap-version works on legacy ES, not
 * just OpenSearch. ES uses the straight-decimal {@code Mmrrbb} encoding
 * (e.g. 7.10.2 = 7_100_299) with NO XOR mask, while OpenSearch flips
 * bit 27 via {@code 0x08000000}.
 */
@Tag("e2e")
class ElasticsearchDowngradeE2ETest {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchDowngradeE2ETest.class);

    private static final String SOURCE_VERSION = "7.17.28";
    private static final String TARGET_VERSION = "7.17.0";
    private static final String BUCKET = "snapshots";
    private static final String BASE_PATH = "es-version-repro";
    private static final String INDEX_NAME = "movies";
    private static final String SNAPSHOT_NAME = "snap-v717";

    private static Network network;
    @SuppressWarnings("rawtypes")
    private static GenericContainer minio;
    private static ElasticsearchTestContainer source;
    private static ElasticsearchTestContainer target;

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

        source = new ElasticsearchTestContainer(SOURCE_VERSION, network, "es-source", "minio:9000");
        target = new ElasticsearchTestContainer(TARGET_VERSION, network, "es-target", "minio:9000");
        source.start();
        target.start();
        log.info("source ES {}: {}", SOURCE_VERSION, source.getHttpEndpoint());
        log.info("target ES {}: {}", TARGET_VERSION, target.getHttpEndpoint());

        http = new OkHttpClient.Builder().callTimeout(Duration.ofMinutes(2)).build();

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
    void esPatchDowngradeFlow_failsBefore_succeedsAfter() throws Exception {
        // 1. Index docs on the ES 7.17 source and snapshot to S3
        putJson(source, "/" + INDEX_NAME, """
                {
                  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
                  "mappings": { "properties": {
                    "title":  { "type": "text" },
                    "year":   { "type": "integer" },
                    "rating": { "type": "float" }
                  }}
                }
                """);
        postJson(source, "/" + INDEX_NAME + "/_bulk?refresh=true",
                """
                {"index":{"_id":"1"}}
                {"title":"The Matrix","year":1999,"rating":8.7}
                {"index":{"_id":"2"}}
                {"title":"Inception","year":2010,"rating":8.8}
                {"index":{"_id":"3"}}
                {"title":"Interstellar","year":2014,"rating":8.6}
                """);
        putJson(source, "/_snapshot/repo-source",
                "{\"type\":\"s3\",\"settings\":{\"bucket\":\"" + BUCKET + "\",\"base_path\":\"" + BASE_PATH + "\"}}");
        putJson(source, "/_snapshot/repo-source/" + SNAPSHOT_NAME + "?wait_for_completion=true",
                "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":true}");
        log.info("STEP 1 OK: snapshot taken on source ES {}", SOURCE_VERSION);

        // 2. Try to restore on the older ES 7.10.2 target -> expect rejection
        putJson(target, "/_snapshot/repo-target",
                "{\"type\":\"s3\",\"settings\":{\"bucket\":\"" + BUCKET + "\",\"base_path\":\"" + BASE_PATH + "\"}}");

        int failCode;
        String failBody;
        try (Response r = postRaw(target,
                "/_snapshot/repo-target/" + SNAPSHOT_NAME + "/_restore?wait_for_completion=true",
                "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":false}")) {
            failCode = r.code();
            failBody = r.body() == null ? "" : r.body().string();
        }
        assertThat(failCode).as("Expected ES %s to REJECT the %s snapshot", TARGET_VERSION, SOURCE_VERSION)
                .isEqualTo(500);
        assertThat(failBody)
                .contains("snapshot_restore_exception");
        // Either "higher than" (modern ES) or "incompatible" (older form) accepted.
        assertThat(failBody.toLowerCase())
                .satisfiesAnyOf(
                        s -> assertThat(s).contains("higher than"),
                        s -> assertThat(s).contains("incompatible"));
        log.info("STEP 2 OK: target ES {} rejected the {} snapshot (HTTP {})",
                TARGET_VERSION, SOURCE_VERSION, failCode);

        // 3. Inspect with VersionRewriter; auto-detects ELASTICSEARCH flavor.
        try (SnapshotStore store = S3Store.open(
                "s3://" + BUCKET + "/" + BASE_PATH,
                "us-east-1", minioS3Url, "minioadmin", "minioadmin", true, null)) {

            VersionRewriter rw = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rw.inventory();

            assertThat(inv.snapshots()).hasSize(1);
            VersionRewriter.SnapshotEntry e = inv.snapshots().get(0);
            assertThat(e.versionStringFromIndexN()).isEqualTo(SOURCE_VERSION);
            assertThat(e.parsedVersion().asString()).isEqualTo(SOURCE_VERSION);
            assertThat(e.detectedFlavor()).isEqualTo(VersionCodec.Flavor.ELASTICSEARCH);
            // 7.17.28 -> straight decimal, no mask
            assertThat(e.versionIdFromSnapDat()).isEqualTo(7_172_899);

            // 4. Rewrite to 7.10.2; rewriter must keep ES flavor (no XOR mask).
            VersionCodec.Parsed targetParsed = VersionCodec.parse(TARGET_VERSION);
            VersionRewriter.RewritePlan plan = rw.plan(targetParsed, inv.snapshots());
            List<String> changes = rw.apply(inv, plan);

            assertThat(changes).hasSize(2);
            assertThat(changes.get(0)).contains("index-").contains(SOURCE_VERSION + " -> " + TARGET_VERSION);
            // 7.17.28 -> 7.17.0 in the *unmasked* legacy ES form
            assertThat(changes.get(1))
                    .contains("snap-")
                    .contains("7172899 -> 7170099");
        }
        log.info("STEP 3 OK: rewrote to ES {} (legacy unmasked flavor)", TARGET_VERSION);

        // 5. Re-register and re-attempt restore -> should succeed
        deleteIfPresent(target, "/_snapshot/repo-target");
        putJson(target, "/_snapshot/repo-target",
                "{\"type\":\"s3\",\"settings\":{\"bucket\":\"" + BUCKET + "\",\"base_path\":\"" + BASE_PATH + "\"}}");

        try (Response r = postRaw(target,
                "/_snapshot/repo-target/" + SNAPSHOT_NAME + "/_restore?wait_for_completion=true",
                "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":false}")) {
            String body = r.body() == null ? "" : r.body().string();
            assertThat(r.code()).as("Expected ES %s to ACCEPT the rewritten snapshot", TARGET_VERSION).isEqualTo(200);
            assertThat(body).contains("\"successful\":1").contains("\"failed\":0");
        }
        log.info("STEP 4 OK: restore succeeded on ES {}", TARGET_VERSION);

        // 6. Verify documents are queryable
        try (Response r = http.newCall(new Request.Builder()
                .url(target.getHttpEndpoint() + "/" + INDEX_NAME + "/_count").build()).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            assertThat(r.code()).isEqualTo(200);
            assertThat(body).contains("\"count\":3");
        }
        log.info("STEP 5 OK: 3 documents queryable on ES {}", TARGET_VERSION);
    }

    /* ----------------------- HTTP helpers ----------------------- */

    private static final MediaType JSON = MediaType.parse("application/json");

    private static void putJson(ElasticsearchTestContainer c, String path, String body) throws IOException {
        try (Response r = http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).put(RequestBody.create(body, JSON)).build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("PUT " + path + " -> " + r.code() + ": "
                        + (r.body() == null ? "" : r.body().string()));
            }
        }
    }

    private static void postJson(ElasticsearchTestContainer c, String path, String body) throws IOException {
        try (Response r = http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).post(RequestBody.create(body, JSON)).build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("POST " + path + " -> " + r.code() + ": "
                        + (r.body() == null ? "" : r.body().string()));
            }
        }
    }

    private static Response postRaw(ElasticsearchTestContainer c, String path, String body) throws IOException {
        return http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).post(RequestBody.create(body, JSON)).build()).execute();
    }

    private static void deleteIfPresent(ElasticsearchTestContainer c, String path) {
        try (Response r = http.newCall(new Request.Builder().url(c.getHttpEndpoint() + path).delete().build()).execute()) {
            r.body();
        } catch (Exception ignored) {
        }
    }
}
