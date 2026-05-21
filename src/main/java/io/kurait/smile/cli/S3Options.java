package io.kurait.smile.cli;

import picocli.CommandLine.Option;

/**
 * Reusable option block: how to talk to S3 (real AWS or MinIO).
 *
 * <p>Use as a {@code @Mixin} on subcommands.
 */
public class S3Options {

    @Option(names = {"--repo"}, required = true,
            description = "Snapshot repo location \u2014 either an s3:// URI (e.g. s3://my-bucket/path) " +
                    "OR a local directory path. S3-only flags below are ignored for local paths.")
    public String repo;

    @Option(names = {"--region"}, defaultValue = "us-east-1",
            description = "AWS region (default: ${DEFAULT-VALUE}).")
    public String region;

    @Option(names = {"--endpoint"},
            description = "Custom S3 endpoint URL (for MinIO / localstack / S3-compatible).")
    public String endpoint;

    @Option(names = {"--profile"},
            description = "AWS profile to use from ~/.aws/credentials and ~/.aws/config.")
    public String profile;

    @Option(names = {"--access-key"},
            description = "Override AWS access key id (default: from AWS credential chain).")
    public String accessKey;

    @Option(names = {"--secret-key"},
            description = "Override AWS secret access key.")
    public String secretKey;

    @Option(names = {"--path-style"}, defaultValue = "false",
            description = "Use path-style addressing (required for MinIO).")
    public boolean pathStyle;
}
