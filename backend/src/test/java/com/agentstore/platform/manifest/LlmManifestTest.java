package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LlmManifestTest {

    final ObjectMapper mapper = new ObjectMapper();
    final ManifestParser parser = new ManifestParser();
    final ManifestValidator validator = new ManifestValidator();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    /** valid-llm-platform.json with its llm block replaced, or removed when {@code llmJson} is null. */
    String withLlm(String llmJson) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(read("valid-llm-platform.json"));
        if (llmJson == null) {
            root.remove("llm");
        } else {
            root.set("llm", mapper.readTree(llmJson));
        }
        return root.toString();
    }

    String withLlmAndAllowlist(String llmJson, String... hosts) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(withLlm(llmJson));
        var allowlist = root.putObject("network").putArray("allowlist");
        for (String host : hosts) {
            allowlist.add(host);
        }
        return root.toString();
    }

    @Test
    void parsesThePlatformBlock() throws Exception {
        Manifest manifest = parser.parse(read("valid-llm-platform.json"));

        assertThat(manifest.llm().mode()).isEqualTo(LlmMode.PLATFORM);
        assertThat(manifest.llm().models()).containsExactly("test-claude", "test-gpt", "test-disabled");
        assertThat(manifest.llm().maxTokensPerRun()).isEqualTo(100_000L);
        assertThat(manifest.llm().provider()).isNull();
        assertThat(validator.validate(read("valid-llm-platform.json")).errors()).isEmpty();
    }

    @Test
    void parsesByokBlocks() throws Exception {
        Manifest proxy = parser.parse(read("valid-llm-byok-proxy.json"));
        Manifest direct = parser.parse(read("valid-llm-byok-direct.json"));

        assertThat(proxy.llm().mode()).isEqualTo(LlmMode.BYOK_PROXY);
        assertThat(proxy.llm().provider()).isEqualTo(LlmVendor.ANTHROPIC);
        assertThat(direct.llm().mode()).isEqualTo(LlmMode.BYOK_DIRECT);
        assertThat(validator.validate(read("valid-llm-byok-proxy.json")).errors()).isEmpty();
        assertThat(validator.validate(read("valid-llm-byok-direct.json")).errors()).isEmpty();
        assertThat(LlmMode.BYOK_PROXY.isByok()).isTrue();
        assertThat(LlmMode.PLATFORM.isByok()).isFalse();
    }

    @Test
    void aManifestWithoutLlmStaysValidAndHasNoLlm() throws Exception {
        assertThat(parser.parse(withLlm(null)).llm()).isNull();
        assertThat(validator.validate(withLlm(null)).valid()).isTrue();
        assertThat(parser.parse(read("valid-tier-a.json")).llm()).isNull();
    }

    @Test
    void theProviderIsRequiredExactlyForByokModes() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","provider":"anthropic","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_direct","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","provider":"google","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","provider":"openai","models":["m"],"maxTokensPerRun":10}""")).valid()).isTrue();
    }

    @Test
    void modelsMustBeANonEmptyListOfDistinctIds() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":[],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["a","a"],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":[""],"maxTokensPerRun":10}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","maxTokensPerRun":10}""")).valid()).isFalse();
    }

    @Test
    void maxTokensPerRunIsAPositiveIntegerWithinThePlatformLimit() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":0}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":10.5}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"]}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":5000000}""")).valid()).isTrue();

        var tooBig = validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":5000001}"""));
        assertThat(tooBig.valid()).isFalse();
        assertThat(tooBig.errors()).anyMatch(e -> e.contains("maxTokensPerRun") && e.contains("5000000"));

        ManifestValidator strict = new ManifestValidator(1000);
        assertThat(strict.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":1000}""")).valid()).isTrue();
        assertThat(strict.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":1001}""")).valid()).isFalse();
    }

    @Test
    void byokDirectNeedsTheProviderHostInTheNetworkAllowlist() throws Exception {
        String direct = """
                {"mode":"byok_direct","provider":"anthropic","models":["m"],"maxTokensPerRun":10}""";
        var missing = validator.validate(withLlm(direct));
        assertThat(missing.valid()).isFalse();
        assertThat(missing.errors()).anyMatch(e -> e.contains("api.anthropic.com"));

        assertThat(validator.validate(withLlmAndAllowlist(direct, "api.openai.com")).valid()).isFalse();
        assertThat(validator.validate(withLlmAndAllowlist(direct, "api.anthropic.com")).valid()).isTrue();

        String openai = """
                {"mode":"byok_direct","provider":"openai","models":["m"],"maxTokensPerRun":10}""";
        assertThat(validator.validate(withLlmAndAllowlist(openai, "api.anthropic.com")).valid()).isFalse();
        assertThat(validator.validate(withLlmAndAllowlist(openai, "api.openai.com")).valid()).isTrue();

        // byok_proxy needs no egress hole
        assertThat(validator.validate(withLlm("""
                {"mode":"byok_proxy","provider":"anthropic","models":["m"],"maxTokensPerRun":10}""")).valid()).isTrue();
    }

    @Test
    void unknownKeysInTheBlockAreRejected() throws Exception {
        assertThat(validator.validate(withLlm("""
                {"mode":"platform","models":["m"],"maxTokensPerRun":10,"stream":true}""")).valid()).isFalse();
        assertThat(validator.validate(withLlm("""
                {"mode":"free_for_all","models":["m"],"maxTokensPerRun":10}""")).valid()).isFalse();
    }

    @Test
    void theOlderModelRequirementsFieldIsStillIgnored() throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(withLlm(null));
        root.putObject("modelRequirements").put("provider", "anthropic").put("model", "claude-sonnet-5");

        assertThat(validator.validate(root.toString()).valid()).isTrue();
        assertThat(parser.parse(root.toString()).llm()).isNull();
    }
}
