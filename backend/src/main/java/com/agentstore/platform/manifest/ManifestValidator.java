package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ManifestValidator {

    public static final long DEFAULT_MAX_TOKENS_PER_RUN_LIMIT = 5_000_000L;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ManifestParser parser = new ManifestParser();
    private final JsonSchema schema;
    private final long maxTokensPerRunLimit;

    public ManifestValidator() {
        this(DEFAULT_MAX_TOKENS_PER_RUN_LIMIT);
    }

    @Autowired
    public ManifestValidator(@Value("${agentstore.llm.max-tokens-per-run-limit:5000000}") long maxTokensPerRunLimit) {
        this.maxTokensPerRunLimit = maxTokensPerRunLimit;
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
        if (!errors.isEmpty()) {
            return new Result(false, errors.stream().map(Object::toString).collect(Collectors.toList()));
        }
        Manifest manifest = parser.parse(rawJson);
        List<String> semantic = new ArrayList<>(ResourceManifestRules.check(manifest));
        semantic.addAll(LlmManifestRules.check(manifest, maxTokensPerRunLimit));
        return semantic.isEmpty() ? new Result(true, List.of()) : new Result(false, semantic);
    }

    public record Result(boolean valid, List<String> errors) {
    }
}
