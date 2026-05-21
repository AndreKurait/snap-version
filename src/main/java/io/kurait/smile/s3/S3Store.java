package io.kurait.smile.s3;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thin S3 wrapper. Supports real AWS S3 *and* MinIO / S3-compatible endpoints
 * via {@code --endpoint} + {@code --access-key} / {@code --secret-key}.
 *
 * <p>Holds the {@code (bucket, prefix)} pair derived from an {@code s3://...} URI
 * and exposes {@link #get(String)}, {@link #put(String, byte[])}, {@link #list(String)}.
 */
public class S3Store implements SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(S3Store.class);
    private static final Pattern S3_URI = Pattern.compile("^s3://([^/]+)(?:/(.*))?$");

    private final S3Client s3;
    private final String bucket;
    private final String prefix; // never starts with "/", always ends with "/" (or empty)

    private S3Store(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    public static S3Store open(String s3Uri, String region, String endpoint,
                               String accessKey, String secretKey, boolean pathStyle) {
        return open(s3Uri, region, endpoint, accessKey, secretKey, pathStyle, null);
    }

    public static S3Store open(String s3Uri, String region, String endpoint,
                               String accessKey, String secretKey, boolean pathStyle,
                               String profile) {
        Matcher m = S3_URI.matcher(s3Uri);
        if (!m.matches()) {
            throw new IllegalArgumentException("not an s3:// URI: " + s3Uri);
        }
        String bucket = m.group(1);
        String prefix = m.group(2) == null ? "" : m.group(2);
        if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix = prefix + "/";

        var builder = S3Client.builder()
                .region(Region.of(region == null ? "us-east-1" : region));

        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        } else if (profile != null && !profile.isEmpty()) {
            builder.credentialsProvider(
                    ProfileCredentialsProvider.builder().profileName(profile).build());
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build());
        }
        if (endpoint != null && !endpoint.isEmpty()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        if (pathStyle) {
            builder.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        S3Client client = builder.build();
        log.info("Opened S3Store: bucket={}, prefix={}, endpoint={}, region={}, profile={}",
                bucket, prefix, endpoint, region, profile);
        return new S3Store(client, bucket, prefix);
    }

    public String getBucket() { return bucket; }
    public String getPrefix() { return prefix; }

    /** Fetch an object by repo-relative key (joined with the configured prefix). */
    public byte[] get(String relativeKey) {
        String key = prefix + relativeKey;
        log.debug("S3 GET s3://{}/{}", bucket, key);
        ResponseBytes<GetObjectResponse> resp = s3.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return resp.asByteArray();
    }

    /** Write an object by repo-relative key. */
    public void put(String relativeKey, byte[] body) {
        String key = prefix + relativeKey;
        log.info("S3 PUT s3://{}/{} ({} bytes)", bucket, key, body.length);
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(body));
    }

    /** List repo-relative keys under {@code subPrefix} (recursive). */
    public List<String> list(String subPrefix) {
        List<String> out = new ArrayList<>();
        String full = prefix + (subPrefix == null ? "" : subPrefix);
        String continuation = null;
        do {
            var req = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(full)
                    .continuationToken(continuation)
                    .build();
            ListObjectsV2Response resp = s3.listObjectsV2(req);
            for (S3Object o : resp.contents()) {
                String k = o.key();
                if (k.startsWith(prefix)) k = k.substring(prefix.length());
                out.add(k);
            }
            continuation = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
        } while (continuation != null);
        return out;
    }

    /** List immediate children (single level) under {@code subPrefix}, returning bare names. */
    public List<String> listFilesAtLevel(String subPrefix) {
        List<String> out = new ArrayList<>();
        String full = prefix + (subPrefix == null ? "" : subPrefix);
        var req = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(full)
                .delimiter("/")
                .build();
        ListObjectsV2Response resp = s3.listObjectsV2(req);
        for (S3Object o : resp.contents()) {
            String k = o.key();
            String name = k.substring(full.length());
            if (!name.contains("/") && !name.isEmpty()) out.add(name);
        }
        return out;
    }

    @Override
    public String describe() {
        return "s3://" + bucket + "/" + prefix;
    }

    @Override
    public void close() {
        s3.close();
    }
}
