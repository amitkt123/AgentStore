package com.agentstore.platform.data;

import com.agentstore.platform.manifest.DataFormat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/** Decides a file's format from its name and its first bytes. Empty means "not something we accept" (415). */
public final class FormatDetector {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private FormatDetector() {
    }

    public static Optional<DataFormat> detect(String filename, Path file) throws IOException {
        byte[] head = head(file, 1024);
        boolean pdf = startsWith(head, PDF_MAGIC);
        String extension = extension(filename);
        switch (extension) {
            case "pdf":
                return pdf ? Optional.of(DataFormat.PDF) : Optional.empty();
            case "csv":
                return pdf ? Optional.empty() : Optional.of(DataFormat.CSV);
            case "json":
                return pdf ? Optional.empty() : Optional.of(DataFormat.JSON);
            default:
                if (pdf) {
                    return Optional.of(DataFormat.PDF);
                }
                return firstSignificantByte(head) == '[' ? Optional.of(DataFormat.JSON) : Optional.empty();
        }
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static byte[] head(Path file, int max) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(max);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** Skips a UTF-8 BOM and whitespace. */
    private static int firstSignificantByte(byte[] bytes) {
        int i = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < bytes.length && Character.isWhitespace(bytes[i])) {
            i++;
        }
        return i < bytes.length ? bytes[i] : -1;
    }
}
