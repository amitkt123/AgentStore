package com.agentstore.runner.api;

public record LogChunkResponse(String data, long nextOffset, boolean eof) {}
