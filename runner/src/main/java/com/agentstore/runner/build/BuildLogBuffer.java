package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildLog;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** In-memory build log: redacted, size-capped, safe to snapshot while a build is still appending. */
public class BuildLogBuffer implements BuildLog {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final String TRUNCATED = "[build log truncated: size limit reached]\n";

    private static final Pattern PEM_BEGIN = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern PEM_END = Pattern.compile("-----END [A-Z ]*PRIVATE KEY-----");

    private final int maxBytes;
    private final StringBuilder text = new StringBuilder();
    private int bytes;
    private boolean truncated;
    private boolean insidePrivateKeyBlock;

    public BuildLogBuffer() {
        this(MAX_BYTES);
    }

    BuildLogBuffer(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public synchronized void append(String line) {
        if (truncated) {
            return;
        }
        String stripped = line == null ? "" : line.stripTrailing();
        String masked;
        if (insidePrivateKeyBlock) {
            masked = LogRedactor.MASK;
            if (PEM_END.matcher(stripped).find()) {
                insidePrivateKeyBlock = false;
            }
        } else if (PEM_BEGIN.matcher(stripped).find()) {
            insidePrivateKeyBlock = true;
            masked = LogRedactor.MASK;
        } else {
            masked = LogRedactor.redact(stripped);
        }
        String entry = masked + "\n";
        int size = entry.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + size > maxBytes) {
            text.append(TRUNCATED);
            truncated = true;
            return;
        }
        text.append(entry);
        bytes += size;
    }

    public synchronized byte[] snapshot() {
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }
}
