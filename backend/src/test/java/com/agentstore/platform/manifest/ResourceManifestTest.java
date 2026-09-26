package com.agentstore.platform.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceManifestTest {

    final ObjectMapper mapper = new ObjectMapper();
    final ManifestParser parser = new ManifestParser();
    final ManifestValidator validator = new ManifestValidator();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    /** valid-runtime-agent.json declares scopes on bank_statement, invoices (READ) and journal_entries (WRITE). */
    String runtimeAgentWithResources(String resourcesJson) throws Exception {
        ObjectNode root = (ObjectNode) mapper.readTree(read("valid-runtime-agent.json"));
        root.set("resources", mapper.readTree(resourcesJson));
        return root.toString();
    }

    static final String ALL_THREE = """
            [{"name":"bank_statement","format":"csv"},{"name":"invoices","format":"csv"},{"name":"journal_entries","format":"json"}]""";

    @Test
    void parsesResourcesWithTypedSchemas() throws Exception {
        Manifest manifest = parser.parse(read("valid-data-agent.json"));

        assertThat(manifest.resources()).extracting(ResourceDecl::name)
                .containsExactly("bank_statement", "statement_pdf", "journal_entries", "audit_log");
        ResourceDecl statement = manifest.resource("bank_statement").orElseThrow();
        assertThat(statement.format()).isEqualTo(DataFormat.CSV);
        assertThat(statement.schema().required()).containsExactly("date", "narration", "amount");
        assertThat(statement.schema().properties()).containsEntry("date", ColumnType.DATE)
                .containsEntry("amount", ColumnType.NUMBER);
        assertThat(manifest.resource("statement_pdf").orElseThrow().schema()).isNull();
        assertThat(manifest.resource("nope")).isEmpty();
    }

    @Test
    void aManifestWithoutResourcesStaysUntyped() throws Exception {
        Manifest manifest = parser.parse(read("valid-tier-a.json"));

        assertThat(manifest.resources()).isNull();
        assertThat(manifest.declaresResources()).isFalse();
        assertThat(manifest.resource("bank_statement")).isEmpty();
        assertThat(validator.validate(read("valid-tier-a.json")).valid()).isTrue();
    }

    @Test
    void theDataAgentManifestIsValid() throws Exception {
        var result = validator.validate(read("valid-data-agent.json"));
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void acceptsResourcesThatCoverEveryScope() throws Exception {
        assertThat(validator.validate(runtimeAgentWithResources(ALL_THREE)).valid()).isTrue();
    }

    @Test
    void rejectsADuplicateResourceName() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv"},{"name":"bank_statement","format":"json"},
                 {"name":"invoices","format":"csv"},{"name":"journal_entries","format":"json"}]"""));
        assertThat(result.valid()).isFalse();
        assertThat(String.join(";", result.errors())).contains("declared twice");
    }

    @Test
    void rejectsAScopeThatNamesAnUndeclaredResourceWhenResourcesArePresent() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv"},{"name":"journal_entries","format":"json"}]"""));
        assertThat(result.valid()).isFalse();
        assertThat(String.join(";", result.errors())).contains("'invoices'").contains("not declared");
    }

    @Test
    void rejectsWritingAPdfResource() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv"},{"name":"invoices","format":"csv"},
                 {"name":"journal_entries","format":"pdf"}]"""));
        assertThat(result.valid()).isFalse();
        assertThat(String.join(";", result.errors())).contains("can only be READ");
    }

    @Test
    void rejectsASchemaOnAPdfResource() throws Exception {
        var result = validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"pdf","schema":{"required":["a"]}},{"name":"invoices","format":"csv"},
                 {"name":"journal_entries","format":"json"}]"""));
        assertThat(result.valid()).isFalse();
    }

    @Test
    void rejectsAnUnknownColumnTypeAndAnUnknownFormat() throws Exception {
        assertThat(validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"csv","schema":{"properties":{"amount":{"type":"decimal"}}}},
                 {"name":"invoices","format":"csv"},{"name":"journal_entries","format":"json"}]""")).valid()).isFalse();
        assertThat(validator.validate(runtimeAgentWithResources("""
                [{"name":"bank_statement","format":"xlsx"},{"name":"invoices","format":"csv"},
                 {"name":"journal_entries","format":"json"}]""")).valid()).isFalse();
    }
}
