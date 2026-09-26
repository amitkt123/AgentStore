package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BundleManifestParsingTest {

    static String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    @Test
    void parsesRuntimeAndEntrypointWhenPresent() throws Exception {
        Manifest m = new ManifestParser().parse(read("valid-bundle-python.json"));
        assertThat(m.runtime()).isEqualTo("python-3.12");
        assertThat(m.entrypoint()).isEqualTo("agent/main.py");
    }

    @Test
    void leavesThemNullWhenAbsent() throws Exception {
        Manifest m = new ManifestParser().parse(read("valid-tier-a.json"));
        assertThat(m.runtime()).isNull();
        assertThat(m.entrypoint()).isNull();
    }

    @Test
    void theSchemaAcceptsTheBundleManifestAndRejectsBadValues() throws Exception {
        ManifestValidator validator = new ManifestValidator();
        String good = read("valid-bundle-python.json");
        assertThat(validator.validate(good).valid()).isTrue();
        assertThat(validator.validate(good.replace("python-3.12", "ruby-3")).valid()).isFalse();
        assertThat(validator.validate(good.replace("agent/main.py", "/agent/main.py")).valid()).isFalse();
    }
}
