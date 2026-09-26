package com.agentstore.runner.backend.e2b;

import com.agentstore.runner.backend.BuildLog;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Copies the complete lines the shim appends to a local file into a {@link BuildLog} while a long call runs. */
final class LogFileFollower implements AutoCloseable {

    private static final int CHUNK = 1 << 20;

    private final Path file;
    private final BuildLog log;
    private final Thread thread;
    private long position;
    private volatile boolean stopping;

    LogFileFollower(Path file, BuildLog log) {
        this.file = file;
        this.log = log;
        this.thread = Thread.ofVirtual().name("build-log-follower").start(this::loop);
    }

    private void loop() {
        while (!stopping) {
            while (pump()) {
                // keep forwarding while there is more
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Forwards the complete lines appended since the last call. Returns true when it forwarded something. */
    synchronized boolean pump() {
        if (!Files.exists(file)) {
            return false;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long available = channel.size() - position;
            if (available <= 0) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(available, CHUNK));
            channel.position(position);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill
            }
            byte[] bytes = buffer.array();
            int end = buffer.position();
            int lastNewline = -1;
            for (int i = end - 1; i >= 0; i--) {
                if (bytes[i] == '\n') {
                    lastNewline = i;
                    break;
                }
            }
            if (lastNewline < 0) {
                if (end == CHUNK) {   // one line longer than a chunk: forward it as it is
                    log.append(new String(bytes, 0, end, StandardCharsets.UTF_8));
                    position += end;
                    return true;
                }
                return false;
            }
            new String(bytes, 0, lastNewline + 1, StandardCharsets.UTF_8).lines().forEach(log::append);
            position += lastNewline + 1;
            return true;
        } catch (IOException e) {
            return false;   // retried on the next tick; close() makes a final attempt
        }
    }

    @Override
    public void close() {
        stopping = true;
        thread.interrupt();
        try {
            thread.join(Duration.ofSeconds(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        while (pump()) {
            // drain what is left
        }
        flushRemainder();
    }

    /**
     * Forwards whatever is left once nothing more will ever be appended: a final line with no trailing newline
     * yet, which {@link #pump()} deliberately withholds while the follower is still running.
     */
    private synchronized void flushRemainder() {
        if (!Files.exists(file)) {
            return;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long available = channel.size() - position;
            if (available <= 0) {
                return;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) available);
            channel.position(position);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill
            }
            new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8).lines().forEach(log::append);
            position += buffer.position();
        } catch (IOException e) {
            // best effort: the follower is closing anyway
        }
    }
}
