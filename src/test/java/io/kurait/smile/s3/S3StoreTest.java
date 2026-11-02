package io.kurait.smile.s3;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class S3StoreTest {
    @Test
    void truncatedPagesWithoutUsableTokensNeverReturnPartialListings() {
        for (boolean recursive : new boolean[]{false, true}) {
            for (String token : Arrays.asList(null, "", " \t")) {
                ListObjectsV2Response page = ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("repo/index-0").build())
                        .isTruncated(true).nextContinuationToken(token).build();
                try (S3Store store = storeWithPages(List.of(page))) {
                    assertThatThrownBy(() -> list(store, recursive))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("without a usable continuation token");
                }
            }
        }
    }

    @Test
    void repeatedTokensIncludingLongerCyclesAreRejected() {
        for (boolean recursive : new boolean[]{false, true}) {
            for (List<String> tokens : List.of(List.of("a", "a"), List.of("a", "b", "a"))) {
                List<ListObjectsV2Response> pages = tokens.stream().map(token ->
                        ListObjectsV2Response.builder().isTruncated(true)
                                .nextContinuationToken(token).build()).toList();
                try (S3Store store = storeWithPages(pages)) {
                    assertThatThrownBy(() -> list(store, recursive))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("repeated continuation token");
                }
            }
        }
    }

    @Test
    void recursiveListingTraversesDistinctTokensAndStopsOnFinalPage() {
        try (S3Store store = storeWithPages(List.of(
                ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("a")
                        .contents(S3Object.builder().key("repo/index-0").build()).build(),
                ListObjectsV2Response.builder().isTruncated(false).nextContinuationToken("ignored")
                        .contents(S3Object.builder().key("repo/indices/id/meta.dat").build()).build()))) {
            assertThat(store.list("")).containsExactly("index-0", "indices/id/meta.dat");
        }
    }

    private static List<String> list(S3Store store, boolean recursive) {
        return recursive ? store.list("") : store.listFilesAtLevel("");
    }

    private static S3Store storeWithPages(List<ListObjectsV2Response> pages) {
        int[] requests = {0};
        S3Client client = (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(), new Class<?>[]{S3Client.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    if (!method.getName().equals("listObjectsV2")) {
                        throw new AssertionError("Unexpected S3 operation: " + method.getName());
                    }
                    if (requests[0] >= pages.size()) {
                        throw new AssertionError("Listing requested another page instead of rejecting invalid pagination");
                    }
                    return pages.get(requests[0]++);
                });
        return new S3Store(client, "unused-test-bucket", "repo/");
    }

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
