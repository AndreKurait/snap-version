package io.kurait.smile.e2e;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import java.time.Duration;

/**
 * Testcontainers OpenSearch container with the {@code repository-s3} plugin
 * pre-installed and credentials seeded into the keystore at startup, so the
 * cluster can speak to a MinIO sidecar on the same docker network.
 *
 * <ul>
 *   <li>{@code repository-s3} plugin (NOT bundled in OS 2.x) is installed on first boot</li>
 *   <li>{@code s3.client.default.access_key} / {@code .secret_key} added to the
 *       keystore via stdin</li>
 *   <li>{@code s3.client.default.endpoint=&lt;minio-host:port&gt;}, protocol=http,
 *       path-style, region=us-east-1</li>
 *   <li>security plugin disabled so the JUnit HTTP client can hit it</li>
 * </ul>
 */
public class OpenSearchTestContainer extends GenericContainer<OpenSearchTestContainer> {

    private static final int HTTP_PORT = 9200;
    private static final String INIT_SCRIPT_PATH = "/init/opensearch-init.sh";

    private static final String INIT_SCRIPT = """
            #!/bin/bash
            set -e
            cd /usr/share/opensearch
            if ! ls plugins | grep -q repository-s3; then
              echo "[init] installing repository-s3"
              ./bin/opensearch-plugin install --batch repository-s3
            fi
            if [ ! -f config/opensearch.keystore ]; then
              bin/opensearch-keystore create
            fi
            echo "${MINIO_USER:-minioadmin}" | bin/opensearch-keystore add --stdin --force s3.client.default.access_key
            echo "${MINIO_PASS:-minioadmin}" | bin/opensearch-keystore add --stdin --force s3.client.default.secret_key
            exec ./opensearch-docker-entrypoint.sh
            """;

    public OpenSearchTestContainer(String version, Network network, String networkAlias, String minioHostPort) {
        super("opensearchproject/opensearch:" + version);

        withNetwork(network)
                .withNetworkAliases(networkAlias)
                .withExposedPorts(HTTP_PORT)
                .withEnv("discovery.type", "single-node")
                .withEnv("DISABLE_INSTALL_DEMO_CONFIG", "true")
                .withEnv("DISABLE_SECURITY_PLUGIN", "true")
                .withEnv("OPENSEARCH_JAVA_OPTS", "-Xms1g -Xmx1g")
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
