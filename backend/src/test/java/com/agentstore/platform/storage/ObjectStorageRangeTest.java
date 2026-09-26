package com.agentstore.platform.storage;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageRangeTest extends AbstractStorageIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void putFileThenReadAnInclusiveByteRange() throws Exception {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        String key = "tests/" + UUID.randomUUID() + ".txt";

        storage.putFile(key, file);

        assertThat(storage.size(key)).contains(10L);
        try (InputStream in = storage.openRange(key, 3, 6)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("3456");
        }
        try (InputStream in = storage.openRange(key, 8, 9)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("89");
        }
    }
}
