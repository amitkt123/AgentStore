package com.agentstore.platform.build;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Reads a bundle zip once, as a stream: the sha256 of the raw bytes, manifest.json at the root, and the set of file
 * paths. Nothing is written to disk. Unpacking with traversal checks happens in the runner.
 */
@Component
public class BundleInspector {

    public static final int MAX_MANIFEST_BYTES = 256 * 1024;
    public static final int MAX_ENTRIES = 20_000;

    public record Inspection(String sha256, String manifestJson, Set<String> files) {}

    public Inspection inspect(InputStream raw) {
        MessageDigest sha = sha256();
        DigestInputStream digest = new DigestInputStream(raw, sha);
        String manifest = null;
        Set<String> files = new HashSet<>();
        int entries = 0;
        try {
            ZipInputStream zip = new ZipInputStream(digest);
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new BundleRejectedException("Bundle has more than " + MAX_ENTRIES + " entries");
                }
                String name = normalize(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                files.add(name);
                if (name.equals("manifest.json")) {
                    manifest = readCapped(zip);
                }
            }
            digest.transferTo(OutputStream.nullOutputStream());   // hash the central directory too
        } catch (ZipException e) {
            throw new BundleRejectedException("Bundle is not a zip: " + e.getMessage());
        } catch (IOException e) {
            throw new BundleRejectedException("Bundle could not be read: " + e.getClass().getSimpleName());
        }
        if (files.isEmpty()) {
            throw new BundleRejectedException("Bundle is empty or not a zip");
        }
        if (manifest == null) {
            throw new BundleRejectedException("manifest.json is missing from the root of the bundle");
        }
        return new Inspection(HexFormat.of().formatHex(sha.digest()), manifest, files);
    }

    private static String normalize(String name) {
        String n = name;
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        return n;
    }

    private static String readCapped(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = zip.read(buffer)) > 0) {
            if (out.size() + n > MAX_MANIFEST_BYTES) {
                throw new BundleRejectedException("manifest.json is too large (limit 256 KiB)");
            }
            out.write(buffer, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
