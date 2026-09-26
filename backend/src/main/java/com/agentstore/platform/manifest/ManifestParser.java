package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

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
                    s.path("description").asText("")));
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
            gates.add(new ApprovalGateDecl(g.get("trigger").asText(), g.path("description").asText("")));
        }

        List<String> dataClasses = new ArrayList<>();
        for (JsonNode d : root.path("dataClasses")) {
            dataClasses.add(d.asText());
        }

        return new Manifest(root.get("name").asText(), root.get("version").asText(), scopes, allowlist,
                costEnvelope, gates, dataClasses, textOrNull(root, "runtime"), textOrNull(root, "entrypoint"));
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
