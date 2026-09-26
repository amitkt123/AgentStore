package com.agentstore.platform.build;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BundleInspectorTest {

    final BundleInspector inspector = new BundleInspector();

    @Test
    void hashesTheWholeFileAndFindsTheManifestAndFiles() {
        byte[] zip = TestBundles.python("1.0.0");
        var inspection = inspector.inspect(new ByteArrayInputStream(zip));
        assertThat(inspection.sha256()).isEqualTo(TestBundles.sha256(zip));
        assertThat(inspection.manifestJson()).contains("\"runtime\": \"python-3.12\"");
        assertThat(inspection.files()).contains("agent/main.py", "manifest.json", "requirements.txt");
    }

    @Test
    void normalizesDotSlashNames() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("./manifest.json", "{}");
        files.put("./agent/main.py", "x");
        var inspection = inspector.inspect(new ByteArrayInputStream(TestBundles.zip(files)));
        assertThat(inspection.files()).contains("agent/main.py");
        assertThat(inspection.manifestJson()).isEqualTo("{}");
    }

    @Test
    void aMissingManifestIsRejected() {
        byte[] zip = TestBundles.zip(Map.of("agent/main.py", "x"));
        assertThatThrownBy(() -> inspector.inspect(new ByteArrayInputStream(zip)))
                .isInstanceOf(BundleRejectedException.class).hasMessageContaining("manifest.json");
    }

    @Test
    void somethingThatIsNotAZipIsRejected() {
        assertThatThrownBy(() -> inspector.inspect(new ByteArrayInputStream("nope".getBytes())))
                .isInstanceOf(BundleRejectedException.class).hasMessageContaining("not a zip");
    }

    @Test
    void anOversizedManifestIsRejected() {
        byte[] zip = TestBundles.zip(Map.of("manifest.json", "x".repeat(BundleInspector.MAX_MANIFEST_BYTES + 1)));
        assertThatThrownBy(() -> inspector.inspect(new ByteArrayInputStream(zip)))
                .isInstanceOf(BundleRejectedException.class).hasMessageContaining("too large");
    }
}
