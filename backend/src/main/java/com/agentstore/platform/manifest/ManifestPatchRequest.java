package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;

/**
 * One manifest patch op (spec §6): {@code add_read_scope} uses resource/description/requiresApproval;
 * {@code add_resource} uses name/format/schema; {@code add_allowlist_host} uses host; {@code remove_scope} uses
 * type/resource. Unused fields for a given op are null.
 */
public record ManifestPatchRequest(@NotBlank String op, String resource, String description,
                                   Boolean requiresApproval, String name, String format, JsonNode schema,
                                   String host, String type) {
}
