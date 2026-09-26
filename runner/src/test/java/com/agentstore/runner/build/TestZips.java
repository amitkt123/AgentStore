package com.agentstore.runner.build;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds zips in memory. ZipOutputStream accepts any entry name, including hostile ones. */
public final class TestZips {

    private TestZips() {}

    public static byte[] zip(Map<String, String> files) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        files.forEach((name, text) -> bytes.put(name, text.getBytes(StandardCharsets.UTF_8)));
        return zipBytes(bytes);
    }

    public static byte[] zipBytes(Map<String, byte[]> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
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

    /**
     * Hand-builds a single-entry, stored (uncompressed) zip whose entry name is written verbatim from
     * {@code rawName}, with the UTF-8/EFS general-purpose flag left unset. {@code ZipOutputStream} always encodes
     * a {@code String} name as well-formed UTF-8, so it cannot produce a malformed name; this writes the zip
     * structure directly to let tests reproduce a bundle with an unreadable entry name.
     */
    public static byte[] zipWithRawName(byte[] rawName, byte[] content) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        long crcValue = crc.getValue();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int localHeaderOffset = 0;
        writeLE(out, 0x04034b50, 4); // local file header signature
        writeLE(out, 20, 2);         // version needed to extract
        writeLE(out, 0, 2);          // general purpose flag: no EFS/UTF-8 bit
        writeLE(out, 0, 2);          // compression method: stored
        writeLE(out, 0, 2);          // mod time
        writeLE(out, 0x21, 2);       // mod date: 1980-01-01
        writeLE(out, crcValue, 4);
        writeLE(out, content.length, 4); // compressed size
        writeLE(out, content.length, 4); // uncompressed size
        writeLE(out, rawName.length, 2);
        writeLE(out, 0, 2);          // extra field length
        out.writeBytes(rawName);
        out.writeBytes(content);

        int centralDirOffset = out.size();
        writeLE(out, 0x02014b50, 4); // central directory file header signature
        writeLE(out, 20, 2);         // version made by
        writeLE(out, 20, 2);         // version needed to extract
        writeLE(out, 0, 2);          // general purpose flag
        writeLE(out, 0, 2);          // compression method
        writeLE(out, 0, 2);          // mod time
        writeLE(out, 0x21, 2);       // mod date
        writeLE(out, crcValue, 4);
        writeLE(out, content.length, 4);
        writeLE(out, content.length, 4);
        writeLE(out, rawName.length, 2);
        writeLE(out, 0, 2);          // extra field length
        writeLE(out, 0, 2);          // file comment length
        writeLE(out, 0, 2);          // disk number start
        writeLE(out, 0, 2);          // internal file attributes
        writeLE(out, 0, 4);          // external file attributes
        writeLE(out, localHeaderOffset, 4);
        out.writeBytes(rawName);

        int centralDirSize = out.size() - centralDirOffset;
        writeLE(out, 0x06054b50, 4); // end of central directory signature
        writeLE(out, 0, 2);          // number of this disk
        writeLE(out, 0, 2);          // disk where central directory starts
        writeLE(out, 1, 2);          // number of central directory records on this disk
        writeLE(out, 1, 2);          // total number of central directory records
        writeLE(out, centralDirSize, 4);
        writeLE(out, centralDirOffset, 4);
        writeLE(out, 0, 2);          // zip file comment length

        return out.toByteArray();
    }

    private static void writeLE(ByteArrayOutputStream out, long value, int numBytes) {
        for (int i = 0; i < numBytes; i++) {
            out.write((int) ((value >> (8 * i)) & 0xFF));
        }
    }
}
