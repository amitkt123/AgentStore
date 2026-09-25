package com.agentstore.runner.build;

import com.agentstore.runner.backend.BuildLog;

import java.nio.charset.StandardCharsets;

/** In-memory build log: redacted, size-capped, safe to snapshot while a build is still appending. */
public class BuildLogBuffer implements BuildLog {

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final String TRUNCATED = "[build log truncated: size limit reached]\n";

    private final int maxBytes;
    private final StringBuilder text = new StringBuilder();
    private int bytes;
    private boolean truncated;

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
        String entry = LogRedactor.redact(line == null ? "" : line.stripTrailing()) + "\n";
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
