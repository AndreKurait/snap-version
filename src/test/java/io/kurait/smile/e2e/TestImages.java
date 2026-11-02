package io.kurait.smile.e2e;

/** Image built by the Gradle e2e tasks; unit tests never build or run it. */
final class TestImages {
    static final String MINIO = System.getProperty(
            "snapversion.test.minio.image", "snap-version-test-minio:5bf41aff1702");

    private TestImages() {}
}
