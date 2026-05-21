package io.kurait.smile.e2e;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.time.Duration;

/**
 * Testcontainers Elasticsearch container with the {@code repository-s3} plugin
 * pre-installed and credentials seeded into the keystore at startup.
 *
 * <p>Differences from {@link OpenSearchTestContainer}:
 * <ul>
 *   <li>ES 7.x ships with X-Pack security; we disable via {@code xpack.security.enabled=false}.</li>
 *   <li>ES uses {@code elasticsearch.yml} not {@code opensearch.yml}; the keystore CLI
 *       is named {@code elasticsearch-keystore}.</li>
 *   <li>ES 7.x has {@code single-node} discovery natively (same env var name).</li>
 * </ul>
 */
public class ElasticsearchTestContainer extends GenericContainer<ElasticsearchTestContainer> {

    private static final int HTTP_PORT = 9200;
    private static final String INIT_SCRIPT_PATH = "/init/elasticsearch-init.sh";

    private static final String INIT_SCRIPT = """
            #!/bin/bash
            set -e
            cd /usr/share/elasticsearch
            if ! ls plugins | grep -q repository-s3; then
              echo "[init] installing repository-s3"
              ./bin/elasticsearch-plugin install --batch repository-s3
            fi
            if [ ! -f config/elasticsearch.keystore ]; then
              bin/elasticsearch-keystore create
            fi
            echo "${MINIO_USER:-minioadmin}" | bin/elasticsearch-keystore add --stdin --force s3.client.default.access_key
            echo "${MINIO_PASS:-minioadmin}" | bin/elasticsearch-keystore add --stdin --force s3.client.default.secret_key
            exec /usr/local/bin/docker-entrypoint.sh
            """;

    public ElasticsearchTestContainer(String version, Network network, String networkAlias, String minioHostPort) {
        super("docker.elastic.co/elasticsearch/elasticsearch:" + version);

        withNetwork(network)
                .withNetworkAliases(networkAlias)
                .withExposedPorts(HTTP_PORT)
                .withEnv("discovery.type", "single-node")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms1g -Xmx1g")
                .withEnv("s3.client.default.endpoint", minioHostPort)
                .withEnv("s3.client.default.protocol", "http")
                .withEnv("s3.client.default.path_style_access", "true")
                .withEnv("s3.client.default.region", "us-east-1")
                .withEnv("AWS_REGION", "us-east-1")
                .withCopyToContainer(
                        Transferable.of(INIT_SCRIPT.getBytes(), 0755),
                        INIT_SCRIPT_PATH)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint(INIT_SCRIPT_PATH))
                .waitingFor(Wait.forHttp("/_cluster/health")
                        .forPort(HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(5)));
    }

    /** Returns {@code http://host:mappedPort} for use from the JUnit JVM. */
    public String getHttpEndpoint() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }
}
