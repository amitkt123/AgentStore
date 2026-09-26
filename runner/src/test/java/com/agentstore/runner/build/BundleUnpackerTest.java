package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BundleUnpackerTest {

    @TempDir Path work;

    Path target() throws Exception {
        return Files.createDirectory(work.resolve("app"));
    }

    Path write(byte[] zip) throws Exception {
        return Files.write(work.resolve("bundle.zip"), zip);
    }

    void assertInvalid(Runnable unpack, String messagePart) {
        assertThatThrownBy(unpack::run)
                .isInstanceOf(BuildFailedException.class)
                .hasMessageContaining(messagePart)
                .satisfies(e -> assertThat(((BuildFailedException) e).code()).isEqualTo(BuildFailedException.INVALID_BUNDLE));
    }

    @Test
    void unpacksNestedFiles() throws Exception {
        Path zip = write(TestZips.zip(Map.of("manifest.json", "{}", "agent/main.py", "print(1)")));
        Path app = target();
        new BundleUnpacker().unpack(zip, app);
        assertThat(app.resolve("agent/main.py")).hasContent("print(1)");
        assertThat(app.resolve("manifest.json")).exists();
    }

    @Test
    void rejectsParentTraversalAndWritesNothingOutside() throws Exception {
        Path zip = write(TestZips.zip(Map.of("../evil.txt", "x")));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker().unpack(zip, app), "escapes");
        assertThat(work.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void rejectsAbsoluteAndBackslashPaths() throws Exception {
        Path app = target();
        Path absolute = write(TestZips.zip(Map.of("/etc/evil", "x")));
        assertInvalid(() -> new BundleUnpacker().unpack(absolute, app), "Absolute");
        Path backslash = write(TestZips.zip(Map.of("..\\evil.txt", "x")));
        assertInvalid(() -> new BundleUnpacker().unpack(backslash, app), "Absolute");
    }

    @Test
    void rejectsAZipBomb() throws Exception {
        Path zip = write(TestZips.zipBytes(Map.of("big.bin", new byte[10_000])));   // compresses to a few bytes
        Path app = target();
        assertInvalid(() -> new BundleUnpacker(1024, 100).unpack(zip, app), "unpacked-size limit");
    }

    @Test
    void rejectsADirectoryEntryThatCarriesAHugePayload() throws Exception {
        Path zip = write(TestZips.zipBytes(Map.of("dir/", new byte[10_000_000])));   // compresses to a few KB
        Path app = target();
        assertInvalid(() -> new BundleUnpacker(1024, 100).unpack(zip, app), "unpacked-size limit");
    }

    @Test
    void rejectsARootEntryThatCarriesAHugePayload() throws Exception {
        Path zip = write(TestZips.zipBytes(Map.of("./", new byte[10_000_000])));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker(1024, 100).unpack(zip, app), "unpacked-size limit");
    }

    @Test
    void countsSkippedEntriesAndFilesAgainstOneBudget() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("dir/", new byte[600]);
        files.put("a.bin", new byte[600]);
        Path zip = write(TestZips.zipBytes(files));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker(1000, 100).unpack(zip, app), "unpacked-size limit");
    }

    @Test
    void rejectsTooManyEntries() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            files.put("f" + i + ".txt", "x");
        }
        Path zip = write(TestZips.zip(files));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker(1_000_000, 3).unpack(zip, app), "more than 3 entries");
    }

    @Test
    void rejectsDuplicatePathsAfterNormalizing() throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("main.py", "a");
        files.put("./main.py", "b");
        Path zip = write(TestZips.zip(files));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker().unpack(zip, app), "Duplicate");
    }

    @Test
    void rejectsAMalformedNonUtf8EntryName() throws Exception {
        // 0xFF/0xFE are never valid UTF-8 leading bytes, and the general-purpose flag has no EFS/UTF-8 bit set,
        // so java.util.zip's strict decode of the entry name throws IllegalArgumentException("malformed input").
        byte[] rawName = {0x41, (byte) 0xFF, (byte) 0xFE, 0x42};
        Path zip = write(TestZips.zipWithRawName(rawName, "x".getBytes()));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker().unpack(zip, app), "unreadable entry name");
    }

    @Test
    void rejectsAnEntryNameContainingNul() throws Exception {
        // The NUL byte is valid UTF-8, so the zip round-trips fine; it's java.nio.file.Path (root.resolve) that
        // refuses it, throwing InvalidPathException, an IllegalArgumentException subtype.
        Path zip = write(TestZips.zip(Map.of("bad\u0000name", "x")));
        Path app = target();
        assertInvalid(() -> new BundleUnpacker().unpack(zip, app), "unreadable entry name");
    }

    @Test
    void rejectsSomethingThatIsNotAZip() throws Exception {
        Path zip = Files.write(work.resolve("bundle.zip"), "definitely not a zip".getBytes());
        Path app = target();
        assertInvalid(() -> new BundleUnpacker().unpack(zip, app), "empty");
    }

    @Test
    void symlinkEntriesBecomePlainFiles() throws Exception {
        assumeTrue(commandExists("zip"), "zip CLI not installed");
        Path src = Files.createDirectory(work.resolve("src"));
        Files.createSymbolicLink(src.resolve("link"), Path.of("/etc/passwd"));
        Process p = new ProcessBuilder("zip", "-q", "-y", work.resolve("links.zip").toString(), "link")
                .directory(src.toFile()).inheritIO().start();
        assertThat(p.waitFor(10, TimeUnit.SECONDS)).isTrue();

        Path app = target();
        new BundleUnpacker().unpack(work.resolve("links.zip"), app);
        assertThat(Files.isSymbolicLink(app.resolve("link"))).isFalse();
        assertThat(app.resolve("link")).hasContent("/etc/passwd");
    }

    static boolean commandExists(String name) {
        try {
            return new ProcessBuilder("sh", "-c", "command -v " + name).start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
