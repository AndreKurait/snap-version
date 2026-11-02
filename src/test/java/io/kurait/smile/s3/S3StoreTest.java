package io.kurait.smile.s3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class S3StoreTest {
    @Test
    void listsEveryPageOfRootMetadataWithoutContactingS3() {
        List<ListObjectsV2Request> requests = new ArrayList<>();
        S3Client client = (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (!method.getName().equals("listObjectsV2")) {
                        throw new AssertionError("Unexpected S3 operation: " + method.getName());
                    }
                    ListObjectsV2Request request = (ListObjectsV2Request) args[0];
                    requests.add(request);
                    if (requests.size() == 1) {
                        return ListObjectsV2Response.builder()
                                .contents(S3Object.builder().key("repo/index-0").build())
                                .isTruncated(true).nextContinuationToken("next-page").build();
                    }
                    assertThat(requests).hasSize(2);
                    return ListObjectsV2Response.builder()
                            .contents(S3Object.builder().key("repo/snap-second-page.dat").build())
                            .isTruncated(false).build();
                });

        try (S3Store store = new S3Store(client, "unused-test-bucket", "repo/")) {
            assertThat(store.listFilesAtLevel("")).containsExactly("index-0", "snap-second-page.dat");
        }
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).continuationToken()).isNull();
        assertThat(requests.get(1).continuationToken()).isEqualTo("next-page");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.bucket()).isEqualTo("unused-test-bucket");
            assertThat(request.prefix()).isEqualTo("repo/");
            assertThat(request.delimiter()).isEqualTo("/");
        });
    }
}
