package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ManifestParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public Manifest parse(String rawJson) {
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Manifest is not valid JSON", e);
        }

        List<Scope> scopes = new ArrayList<>();
        for (JsonNode s : root.path("scopes")) {
            scopes.add(new Scope(ScopeType.valueOf(s.get("type").asText()), s.get("resource").asText(),
                    s.path("description").asText(""), s.path("requiresApproval").asBoolean(false)));
        }

        List<String> allowlist = new ArrayList<>();
        for (JsonNode n : root.path("network").path("allowlist")) {
            allowlist.add(n.asText());
        }

        JsonNode ce = root.path("costEnvelope");
        CostEnvelope costEnvelope = new CostEnvelope(
                ce.get("currency").asText(),
                new BigDecimal(ce.get("maxPerRun").asText()),
                PricingUnit.valueOf(ce.get("pricingUnit").asText()),
                new BigDecimal(ce.get("unitPrice").asText()),
                ce.get("unitLabel").asText());

        List<ApprovalGateDecl> gates = new ArrayList<>();
        for (JsonNode g : root.path("approvalGates")) {
            gates.add(new ApprovalGateDecl(g.get("trigger").asText(), g.path("description").asText(""),
                    g.path("timeoutMinutes").asInt(ApprovalGateDecl.DEFAULT_TIMEOUT_MINUTES)));
        }

        List<String> dataClasses = new ArrayList<>();
        for (JsonNode d : root.path("dataClasses")) {
            dataClasses.add(d.asText());
        }

        return new Manifest(root.get("name").asText(), root.get("version").asText(), scopes, allowlist,
                costEnvelope, gates, dataClasses, textOrNull(root, "runtime"), textOrNull(root, "entrypoint"),
                parseResources(root), parseLlm(root));
    }

    private static LlmDecl parseLlm(JsonNode root) {
        JsonNode llm = root.get("llm");
        if (llm == null || !llm.isObject()) {
            return null;
        }
        String modeWire = llm.get("mode").asText();
        LlmMode mode = LlmMode.fromWire(modeWire)
                .orElseThrow(() -> new IllegalArgumentException("Unknown llm mode: " + modeWire));
        List<String> models = new ArrayList<>();
        for (JsonNode model : llm.path("models")) {
            models.add(model.asText());
        }
        LlmVendor provider = null;
        if (llm.hasNonNull("provider")) {
            String providerWire = llm.get("provider").asText();
            provider = LlmVendor.fromWire(providerWire)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown llm provider: " + providerWire));
        }
        return new LlmDecl(mode, models, llm.get("maxTokensPerRun").asLong(), provider);
    }

    private static List<ResourceDecl> parseResources(JsonNode root) {
        JsonNode array = root.get("resources");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<ResourceDecl> resources = new ArrayList<>();
        for (JsonNode r : array) {
            String wire = r.get("format").asText();
            DataFormat format = DataFormat.fromWire(wire)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown resource format: " + wire));
            resources.add(new ResourceDecl(r.get("name").asText(), format, parseSchema(r.get("schema"))));
        }
        return resources;
    }

    private static ResourceSchema parseSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) {
            return null;
        }
        List<String> required = new ArrayList<>();
        for (JsonNode name : schema.path("required")) {
            required.add(name.asText());
        }
        Map<String, ColumnType> properties = new LinkedHashMap<>();
        schema.path("properties").fields().forEachRemaining(entry ->
                properties.put(entry.getKey(), ColumnType.fromWire(entry.getValue().path("type").asText(null))));
        return new ResourceSchema(required, properties);
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
