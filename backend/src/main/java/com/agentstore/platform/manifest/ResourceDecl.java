package com.agentstore.platform.manifest;

/** One entry of the manifest's {@code resources[]}. {@code schema} is null when none was declared (always for PDF). */
public record ResourceDecl(String name, DataFormat format, ResourceSchema schema) {
}
