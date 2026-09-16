package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class ManifestValidator {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchema schema;

    public ManifestValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.schema = factory.getSchema(
                getClass().getClassLoader().getResourceAsStream("manifest/manifest-schema.json"));
    }

    public Result validate(String rawJson) {
        JsonNode node;
        try {
            node = mapper.readTree(rawJson);
        } catch (Exception e) {
            return new Result(false, List.of("Manifest is not valid JSON: " + e.getMessage()));
        }
        var errors = schema.validate(node);
        if (errors.isEmpty()) {
            return new Result(true, List.of());
        }
        return new Result(false, errors.stream().map(Object::toString).collect(Collectors.toList()));
    }

    public record Result(boolean valid, List<String> errors) {
    }
}
