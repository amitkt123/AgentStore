package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused tests for {@link BundleFetcher}'s named risks: the size cap, a non-200 response, and a download that
 * never finishes within its budget (spec §4 step 3, review focus 4).
 */
class BundleFetcherTest {

    BundleServer server;
    HttpClient http;

    @BeforeEach
    void setUp() {
        server = new BundleServer();
        http = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void aBundleOverTheCapIsAnInvalidBundle(@TempDir Path dir) {
        byte[] zip = TestZips.zip(Map.of("manifest.json", "{}", "main.py", "print('well over sixteen bytes of content')"));
        server.serve(zip);
        BundleFetcher fetcher = new BundleFetcher(http, 16, Duration.ofSeconds(10));

        assertThatThrownBy(() -> fetcher.fetch(server.bundleUrl(), TestZips.sha256(zip), dir.resolve("bundle.zip")))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo(BuildFailedException.INVALID_BUNDLE))
                .hasMessageContaining("MB");
    }

    @Test
    void aNon200ResponseIsAnInvalidBundle(@TempDir Path dir) {
        BundleFetcher fetcher = new BundleFetcher(http, BundleFetcher.MAX_BUNDLE_BYTES, Duration.ofSeconds(10));

        assertThatThrownBy(() -> fetcher.fetch(server.missingUrl(), "a".repeat(64), dir.resolve("bundle.zip")))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo(BuildFailedException.INVALID_BUNDLE))
                .hasMessageContaining("404")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-signature"));
    }

    @Test
    void aDrippingDownloadTimesOutWithoutLeakingTheUrl(@TempDir Path dir) {
        byte[] zip = TestZips.zip(Map.of("manifest.json", "{}", "main.py", "print('this bundle drips in one-byte chunks')"));
        server.serveSlowly(zip, Duration.ofMillis(200), 1);
        BundleFetcher fetcher = new BundleFetcher(http, BundleFetcher.MAX_BUNDLE_BYTES, Duration.ofMillis(400));

        assertThatThrownBy(() -> fetcher.fetch(server.bundleUrl(), TestZips.sha256(zip), dir.resolve("bundle.zip")))
                .isInstanceOf(BuildFailedException.class)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo(BuildFailedException.INVALID_BUNDLE))
                .hasMessageContaining("timed out")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-signature"));
    }
}
