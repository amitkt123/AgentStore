package com.agentstore.platform.storage;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageTest extends AbstractStorageIntegrationTest {

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aPresignedPutIsReadableThroughTheClientAndAPresignedGet() throws Exception {
        String key = "tests/" + UUID.randomUUID() + ".txt";
        HttpResponse<String> put = http.send(HttpRequest.newBuilder(URI.create(storage.presignPut(key, Duration.ofMinutes(5))))
                .PUT(HttpRequest.BodyPublishers.ofString("hello")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(put.statusCode()).isEqualTo(200);

        assertThat(storage.size(key)).contains(5L);
        try (InputStream in = storage.open(key)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(storage.presignGet(key, Duration.ofMinutes(5))))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).isEqualTo("hello");
    }

    @Test
    void aPresignedPutCanBeReusedToRewriteTheObject() throws Exception {
        String key = "tests/" + UUID.randomUUID() + ".log";
        String url = storage.presignPut(key, Duration.ofMinutes(5));
        for (String body : new String[] {"one\n", "one\ntwo\n"}) {
            HttpResponse<Void> put = http.send(HttpRequest.newBuilder(URI.create(url))
                    .PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            assertThat(put.statusCode()).isEqualTo(200);
        }
        assertThat(storage.size(key)).contains(8L);
    }

    @Test
    void aTamperedSignatureIsRefused() throws Exception {
        String url = storage.presignGet("tests/whatever.txt", Duration.ofMinutes(5)).replaceAll("X-Amz-Signature=[0-9a-f]+", "X-Amz-Signature=00");
        HttpResponse<Void> get = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(get.statusCode()).isEqualTo(403);
    }

    @Test
    void theSizeOfAMissingObjectIsEmptyAndDeleteRemoves() {
        String key = "tests/" + UUID.randomUUID() + ".bin";
        assertThat(storage.size(key)).isEmpty();
        storage.put(key, new byte[] {1, 2, 3});
        assertThat(storage.size(key)).contains(3L);
        storage.deleteQuietly(key);
        assertThat(storage.size(key)).isEmpty();
    }
}
