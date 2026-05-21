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
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end downgrade-and-restore test against real OpenSearch + MinIO containers,
 * spun up via Testcontainers (no shelling out to docker compose).
 *
 * <h3>Scenario</h3>
 * <ol>
 *   <li>Source cluster (OpenSearch <b>2.19.4</b>) indexes 3 docs and snapshots to MinIO.</li>
 *   <li>Target cluster (OpenSearch <b>2.19.0</b>) attempts to restore the snapshot
 *       and is REJECTED with HTTP 500 / {@code snapshot_restore_exception}.</li>
 *   <li>Our {@link VersionRewriter} rewrites the snapshot's version to {@code 2.19.0}
 *       in both {@code index-N} (plain JSON) and {@code snap-&lt;uuid&gt;.dat} (Smile).</li>
 *   <li>Target cluster re-registers the repo and the same restore call now SUCCEEDS;
 *       all 3 documents are queryable.</li>
 * </ol>
 *
 * <p>Tagged {@code @Tag("e2e")} so it doesn't run in the default {@code ./gradlew test}.
 * Run with {@code ./gradlew e2eTest}. Requires a working Docker socket — testcontainers
 * 1.21.4+ supports Docker 29.x.
 */
@Tag("e2e")
@Testcontainers
class SnapshotDowngradeE2ETest {

    private static final Logger log = LoggerFactory.getLogger(SnapshotDowngradeE2ETest.class);

    private static final String SOURCE_VERSION = "2.19.4";
    private static final String TARGET_VERSION = "2.19.0";
    private static final String BUCKET = "snapshots";
    private static final String BASE_PATH = "version-repro";
    private static final String INDEX_NAME = "movies";
    private static final String SNAPSHOT_NAME = "snap-v" + SOURCE_VERSION.replace(".", "");

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

        // MinIO via GenericContainer (no minio module dep needed)
        minio = new GenericContainer("minio/minio:RELEASE.2024-09-13T20-26-02Z")
                .withNetwork(network)
                .withNetworkAliases("minio")
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", "minioadmin")
                .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
                .withCommand("server", "/data")
                .waitingFor(Wait.forHttp("/minio/health/live")
                        .forPort(9000)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        minio.start();

        minioS3Url = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);

        // Two OpenSearch clusters, in parallel where possible
        source = new OpenSearchTestContainer(SOURCE_VERSION, network, "opensearch-source", "minio:9000");
        target = new OpenSearchTestContainer(TARGET_VERSION, network, "opensearch-target", "minio:9000");
        source.start();
        target.start();

        log.info("MinIO  : {}", minioS3Url);
        log.info("Source : {}  ({})", source.getHttpEndpoint(), SOURCE_VERSION);
        log.info("Target : {}  ({})", target.getHttpEndpoint(), TARGET_VERSION);

        http = new OkHttpClient.Builder().callTimeout(Duration.ofMinutes(2)).build();

        // Pre-create the bucket
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
        if (minio  != null) minio.stop();
        if (network != null) network.close();
    }

    @Test
    void downgradeFlow_failsBefore_succeedsAfter() throws Exception {
        // 1. Index docs on source and snapshot to S3
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
        log.info("STEP 1 OK: snapshot taken on source 2.19.4");

        // 2. Register repo on target and try to restore — expect failure
        putJson(target, "/_snapshot/repo-target",
                "{\"type\":\"s3\",\"settings\":{\"bucket\":\"" + BUCKET + "\",\"base_path\":\"" + BASE_PATH + "\"}}");

        Response failResp = postRaw(target,
                "/_snapshot/repo-target/" + SNAPSHOT_NAME + "/_restore?wait_for_completion=true",
                "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":false}");
        String failBody;
        try (Response r = failResp) {
            failBody = r.body() == null ? "" : r.body().string();
            assertThat(r.code()).as("Expected target 2.19.0 to REJECT the 2.19.4 snapshot")
                    .isEqualTo(500);
        }
        assertThat(failBody)
                .contains("snapshot_restore_exception")
                .contains("OpenSearch version [" + SOURCE_VERSION + "]")
                .contains("higher than the version of this node [" + TARGET_VERSION + "]");
        log.info("STEP 2 OK: target rejected as expected");

        // 3. Run our VersionRewriter to downgrade
        try (SnapshotStore store = S3Store.open(
                "s3://" + BUCKET + "/" + BASE_PATH,
                "us-east-1", minioS3Url, "minioadmin", "minioadmin", /*pathStyle=*/ true, null)) {

            VersionRewriter rw = new VersionRewriter(store);
            VersionRewriter.Inventory inv = rw.inventory();

            assertThat(inv.snapshots()).hasSize(1);
            VersionRewriter.SnapshotEntry e = inv.snapshots().get(0);
            assertThat(e.versionStringFromIndexN()).isEqualTo(SOURCE_VERSION);
            assertThat(e.parsedVersion().asString()).isEqualTo(SOURCE_VERSION);

            VersionCodec.Parsed targetParsed = VersionCodec.parse(TARGET_VERSION);
            VersionRewriter.RewritePlan plan = rw.plan(targetParsed, inv.snapshots());
            List<String> changes = rw.apply(inv, plan);

            assertThat(changes).hasSize(2);
            assertThat(changes.get(0)).contains(SOURCE_VERSION + " -> " + TARGET_VERSION);
            assertThat(changes.get(1)).contains("136408227 -> 136407827");
        }
        log.info("STEP 3 OK: VersionRewriter rewrote both files");

        // 4. Re-register repo on target and re-attempt restore — expect success
        deleteIfPresent(target, "/_snapshot/repo-target");
        putJson(target, "/_snapshot/repo-target",
                "{\"type\":\"s3\",\"settings\":{\"bucket\":\"" + BUCKET + "\",\"base_path\":\"" + BASE_PATH + "\"}}");

        Response goodResp = postRaw(target,
                "/_snapshot/repo-target/" + SNAPSHOT_NAME + "/_restore?wait_for_completion=true",
                "{\"indices\":\"" + INDEX_NAME + "\",\"include_global_state\":false}");
        String goodBody;
        try (Response r = goodResp) {
            goodBody = r.body() == null ? "" : r.body().string();
            assertThat(r.code()).as("Expected target 2.19.0 to ACCEPT the now-2.19.0-tagged snapshot")
                    .isEqualTo(200);
        }
        assertThat(goodBody).contains("\"successful\":1").contains("\"failed\":0");
        log.info("STEP 4 OK: restore succeeded");

        // 5. Verify documents are queryable
        try (Response r = http.newCall(new Request.Builder()
                .url(target.getHttpEndpoint() + "/" + INDEX_NAME + "/_count").build()).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            assertThat(r.code()).isEqualTo(200);
            assertThat(body).contains("\"count\":3");
        }
        log.info("STEP 5 OK: 3 documents queryable on target");
    }

    /* ----------------------- HTTP helpers ----------------------- */

    private static final MediaType JSON = MediaType.parse("application/json");

    private static void putJson(OpenSearchTestContainer c, String path, String body) throws IOException {
        try (Response r = http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).put(RequestBody.create(body, JSON)).build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("PUT " + path + " -> " + r.code() + ": "
                        + (r.body() == null ? "" : r.body().string()));
            }
        }
    }

    private static void postJson(OpenSearchTestContainer c, String path, String body) throws IOException {
        try (Response r = http.newCall(new Request.Builder()
                .url(c.getHttpEndpoint() + path).post(RequestBody.create(body, JSON)).build()).execute()) {
            if (!r.isSuccessful()) {
                throw new IOException("POST " + path + " -> " + r.code() + ": "
                        + (r.body() == null ? "" : r.body().string()));
            }
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
}
