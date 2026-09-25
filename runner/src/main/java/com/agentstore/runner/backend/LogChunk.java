package com.agentstore.runner.backend;

/** {@code data} is UTF-8 decoded with replacement; {@code nextOffset} advances by bytes read, not characters. */
public record LogChunk(String data, long nextOffset) {
    public static LogChunk empty(long offset) { return new LogChunk("", offset); }
}
