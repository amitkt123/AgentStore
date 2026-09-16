package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestValidatorTest {

    ManifestValidator validator = new ManifestValidator();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    @Test
    void acceptsValidTierAManifest() throws Exception {
        var result = validator.validate(read("valid-tier-a.json"));
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void acceptsValidTierBManifest() throws Exception {
        var result = validator.validate(read("valid-tier-b.json"));
        assertThat(result.valid()).isTrue();
    }

    @Test
    void rejectsManifestMissingCostEnvelope() throws Exception {
        var result = validator.validate(read("invalid-missing-cost-envelope.json"));
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void rejectsMalformedJson() {
        var result = validator.validate("{not valid json");
        assertThat(result.valid()).isFalse();
    }
}
