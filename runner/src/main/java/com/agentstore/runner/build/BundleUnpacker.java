package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Unpacks a bundle zip into an empty directory, refusing anything that could escape it (spec §4 step 3).
 * java.util.zip ignores Unix modes, so symlink entries are written as plain files: no symlink is ever created.
 */
public class BundleUnpacker {

    public static final long MAX_UNPACKED_BYTES = 500L * 1024 * 1024;
    public static final int MAX_ENTRIES = 20_000;

    private final long maxUnpackedBytes;
    private final int maxEntries;

    public BundleUnpacker() {
        this(MAX_UNPACKED_BYTES, MAX_ENTRIES);
    }

    BundleUnpacker(long maxUnpackedBytes, int maxEntries) {
        this.maxUnpackedBytes = maxUnpackedBytes;
        this.maxEntries = maxEntries;
    }

    public void unpack(Path zip, Path target) {
        Path root = target.toAbsolutePath().normalize();
        long total = 0;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw invalid("Bundle has more than " + maxEntries + " entries");
                }
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.matches("^[A-Za-z]:.*")) {
                    throw invalid("Absolute or Windows-style path in bundle: " + name);
                }
                Path dest = root.resolve(name).normalize();
                if (!dest.startsWith(root)) {
                    throw invalid("Path escapes the bundle: " + name);
                }
                if (dest.equals(root) || entry.isDirectory()) {
                    // Nothing is written for these, but a crafted entry can still carry a huge compressed payload:
                    // inflate it through the same budget so it cannot hold the build slot.
                    total = copyWithinLimit(in, OutputStream.nullOutputStream(), total);
                    if (!dest.equals(root)) {
                        Files.createDirectories(dest);
                    }
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (OutputStream out = Files.newOutputStream(dest, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    total = copyWithinLimit(in, out, total);
                }
            }
        } catch (FileAlreadyExistsException e) {
            throw invalid("Duplicate path in bundle: " + root.relativize(Path.of(e.getFile())));
        } catch (ZipException e) {
            throw invalid("Bundle is not a valid zip: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            // Covers java.util.zip's strict-UTF-8 decode failure on a malformed entry name (getNextEntry()),
            // and java.nio.file.InvalidPathException (an IllegalArgumentException subtype) from an entry name
            // containing a NUL byte, which the filesystem refuses to resolve into a Path.
            throw invalid("Bundle has an unreadable entry name");
        } catch (IOException e) {
            throw invalid("Bundle could not be unpacked: " + e.getClass().getSimpleName());
        }
        if (entries == 0) {
            throw invalid("Bundle is empty or not a zip");
        }
    }

    private long copyWithinLimit(InputStream in, OutputStream out, long total) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > maxUnpackedBytes) {
                throw invalid("Bundle is larger than the unpacked-size limit (" + human(maxUnpackedBytes) + ")");
            }
            out.write(buffer, 0, n);
        }
        return total;
    }

    private static String human(long bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : bytes + " bytes";
    }

    private static BuildFailedException invalid(String reason) {
        return new BuildFailedException(BuildFailedException.INVALID_BUNDLE, reason);
    }
}
