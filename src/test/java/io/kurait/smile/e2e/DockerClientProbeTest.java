package io.kurait.smile.e2e;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: does the Testcontainers Java client successfully connect to the
 * Docker daemon and get a non-empty {@code /info} response? On Docker Desktop
 * for macOS, older versions returned a stub response and threw
 * {@code IllegalStateException("Could not find a valid Docker environment")}.
 *
 * <p>If this fails on your machine, you'll need to use the docker-compose-CLI
 * fallback path (see SnapshotDowngradeE2ETest pre-fallback). With recent
 * testcontainers (1.21+) and Docker Desktop 4.40+ this should pass.
 */
@Tag("e2e")
class DockerClientProbeTest {

    @Test
    void canTalkToDockerDaemon() {
        var client = DockerClientFactory.instance().client();
        var info = client.infoCmd().exec();
        assertThat(info.getServerVersion())
                .as("Docker engine ServerVersion (testcontainers Java client)")
                .isNotEmpty();
        assertThat(info.getOperatingSystem()).isNotEmpty();
        System.out.printf("Docker OK: server=%s, os=%s, arch=%s%n",
                info.getServerVersion(), info.getOperatingSystem(), info.getArchitecture());
    }
}
