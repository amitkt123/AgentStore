package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mutates a DRAFT version's raw manifest JSON tree for one of the four developer-facing patch ops (spec §6),
 * re-validating the result through the existing {@link ManifestValidator} path before it is ever persisted.
 */
@Component
public class ManifestPatchService {

    private final ManifestParser parser;
    private final ManifestValidator validator;
    private final ObjectMapper mapper;

    public ManifestPatchService(ManifestParser parser, ManifestValidator validator, ObjectMapper mapper) {
        this.parser = parser;
        this.validator = validator;
        this.mapper = mapper;
    }

    public record Result(String manifestJson, ManifestPatchResponse.Diff diff) {
    }

    public Result apply(String currentManifestJson, ManifestPatchRequest request, String liveManifestJsonOrNull) {
        ObjectNode root;
        try {
            root = (ObjectNode) mapper.readTree(currentManifestJson);
        } catch (Exception e) {
            throw new ManifestApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CORRUPT_MANIFEST",
                    "The stored manifest is not valid JSON");
        }

        switch (request.op()) {
            case "add_read_scope" -> addScope(root, ScopeType.READ, request);
            case "add_resource" -> addResource(root, request);
            case "add_allowlist_host" -> addAllowlistHost(root, request);
            case "remove_scope" -> removeScope(root, request);
            default -> throw new ManifestApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_OP",
                    "Unknown patch op: " + request.op());
        }

        String patched = root.toString();
        var validation = validator.validate(patched);
        if (!validation.valid()) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST", String.join("; ", validation.errors()));
        }

        Manifest patchedManifest = parser.parse(patched);
        Manifest liveManifest = liveManifestJsonOrNull == null ? null : parser.parse(liveManifestJsonOrNull);
        return new Result(patched, diff(patchedManifest, liveManifest));
    }

    private void addScope(ObjectNode root, ScopeType type, ManifestPatchRequest request) {
        if (request.resource() == null || request.resource().isBlank()) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "resource is required");
        }
        ArrayNode scopes = arrayField(root, "scopes");
        for (JsonNode s : scopes) {
            if (s.path("type").asText().equals(type.name()) && s.path("resource").asText().equals(request.resource())) {
                throw new ManifestApiException(HttpStatus.CONFLICT, "SCOPE_ALREADY_DECLARED",
                        "A " + type + " scope for '" + request.resource() + "' is already declared");
            }
        }
        ObjectNode scope = mapper.createObjectNode();
        scope.put("type", type.name());
        scope.put("resource", request.resource());
        scope.put("description", request.description() == null ? "" : request.description());
        if (request.requiresApproval() != null) {
            scope.put("requiresApproval", request.requiresApproval());
        }
        scopes.add(scope);
    }

    private void addResource(ObjectNode root, ManifestPatchRequest request) {
        if (request.name() == null || request.name().isBlank() || request.format() == null) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "name and format are required");
        }
        ArrayNode resources = arrayField(root, "resources");
        for (JsonNode r : resources) {
            if (r.path("name").asText().equals(request.name())) {
                throw new ManifestApiException(HttpStatus.CONFLICT, "RESOURCE_ALREADY_DECLARED",
                        "A resource named '" + request.name() + "' is already declared");
            }
        }
        ObjectNode resource = mapper.createObjectNode();
        resource.put("name", request.name());
        resource.put("format", request.format().toLowerCase(Locale.ROOT));
        if (request.schema() != null && !request.schema().isNull()) {
            resource.set("schema", request.schema());
        }
        resources.add(resource);
    }

    private void addAllowlistHost(ObjectNode root, ManifestPatchRequest request) {
        if (request.host() == null || request.host().isBlank()) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "host is required");
        }
        ObjectNode network = root.has("network") && root.get("network").isObject()
                ? (ObjectNode) root.get("network") : root.putObject("network");
        ArrayNode allowlist = network.has("allowlist") && network.get("allowlist").isArray()
                ? (ArrayNode) network.get("allowlist") : network.putArray("allowlist");
        for (JsonNode host : allowlist) {
            if (host.asText().equals(request.host())) {
                return;   // idempotent: the host is already allowed
            }
        }
        allowlist.add(request.host());
    }

    private void removeScope(ObjectNode root, ManifestPatchRequest request) {
        if (request.type() == null || request.resource() == null) {
            throw new ManifestApiException(HttpStatus.BAD_REQUEST, "INVALID_PATCH", "type and resource are required");
        }
        ArrayNode scopes = arrayField(root, "scopes");
        for (int i = 0; i < scopes.size(); i++) {
            JsonNode s = scopes.get(i);
            if (s.path("type").asText().equals(request.type()) && s.path("resource").asText().equals(request.resource())) {
                scopes.remove(i);
                return;
            }
        }
        throw new ManifestApiException(HttpStatus.NOT_FOUND, "SCOPE_NOT_FOUND",
                "No " + request.type() + " scope for '" + request.resource() + "' is declared");
    }

    private ArrayNode arrayField(ObjectNode root, String field) {
        return root.has(field) && root.get(field).isArray() ? (ArrayNode) root.get(field) : root.putArray(field);
    }

    private ManifestPatchResponse.Diff diff(Manifest patched, Manifest live) {
        List<String> liveScopes = live == null ? List.of() : scopeKeys(live);
        List<String> patchedScopes = scopeKeys(patched);
        List<String> added = patchedScopes.stream().filter(s -> !liveScopes.contains(s)).toList();
        List<String> removed = liveScopes.stream().filter(s -> !patchedScopes.contains(s)).toList();
        List<String> liveHosts = live == null ? List.of() : live.networkAllowlist();
        List<String> addedHosts = patched.networkAllowlist().stream().filter(h -> !liveHosts.contains(h)).toList();
        return new ManifestPatchResponse.Diff(added, removed, addedHosts);
    }

    private List<String> scopeKeys(Manifest manifest) {
        List<String> keys = new ArrayList<>();
        for (Scope s : manifest.scopes()) {
            keys.add(s.type().name().toLowerCase(Locale.ROOT) + ":" + s.resource());
        }
        return keys;
    }
}
