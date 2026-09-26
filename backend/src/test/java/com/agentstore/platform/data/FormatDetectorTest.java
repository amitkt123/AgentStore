package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FormatDetectorTest {

    @TempDir
    Path dir;

    Optional<DataFormat> detect(String filename, String content) throws Exception {
        Path file = dir.resolve("f");
        Files.writeString(file, content, StandardCharsets.ISO_8859_1);
        return FormatDetector.detect(filename, file);
    }

    @Test
    void usesTheExtensionAndConfirmsPdfByItsMagicBytes() throws Exception {
        assertThat(detect("a.csv", "a,b\n1,2\n")).contains(DataFormat.CSV);
        assertThat(detect("A.CSV", "a,b\n")).contains(DataFormat.CSV);
        assertThat(detect("a.json", "[]")).contains(DataFormat.JSON);
        assertThat(detect("a.pdf", "%PDF-1.7\n...")).contains(DataFormat.PDF);
        assertThat(detect("a.pdf", "not a pdf")).isEmpty();
        assertThat(detect("a.csv", "%PDF-1.7\n...")).isEmpty();          // a PDF wearing a .csv name
    }

    @Test
    void sniffsWhenThereIsNoUsableExtension() throws Exception {
        assertThat(detect("export", "%PDF-1.4")).contains(DataFormat.PDF);
        assertThat(detect("export", "  \n[{\"a\":1}]")).contains(DataFormat.JSON);
        assertThat(detect("export", "a,b\n1,2")).isEmpty();               // CSV cannot be told from prose
        assertThat(detect("notes.exe", "MZ\u0090")).isEmpty();
        assertThat(detect("", "")).isEmpty();
    }
}
