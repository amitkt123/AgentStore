package com.agentstore.platform.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestBundles {

    private TestBundles() {}

    public static String manifest(String version) {
        try {
            return Files.readString(Path.of("src/test/resources/manifest/valid-bundle-python.json"))
                    .replace("\"version\": \"1.0.0\"", "\"version\": \"" + version + "\"");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A valid python bundle: manifest (runtime python-3.12, entrypoint agent/main.py), code and requirements. */
    public static byte[] python(String version) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("manifest.json", manifest(version));
        files.put("agent/main.py", "if __name__ == '__main__':\n    print('hi')\n");
        files.put("requirements.txt", "");
        return zip(files);
    }

    public static byte[] zip(Map<String, String> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
