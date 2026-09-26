package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestRuntimeFieldsTest {

    private final ManifestParser parser = new ManifestParser();
    private final ManifestValidator validator = new ManifestValidator();

    private static String manifest(String scope, String gate) {
        return """
                {"name":"A","version":"1.0.0","scopes":[%s],"network":{"allowlist":[]},
                 "costEnvelope":{"currency":"INR","maxPerRun":10,"pricingUnit":"METERED","unitPrice":1,"unitLabel":"u"},
                 "approvalGates":[%s],"dataClasses":[]}""".formatted(scope, gate);
    }

    private static final String PLAIN_SCOPE = "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\"}";
    private static final String PLAIN_GATE = "{\"trigger\":\"g\",\"description\":\"d\"}";

    @Test
    void newFieldsDefaultWhenAbsent() {
        Manifest parsed = parser.parse(manifest(PLAIN_SCOPE, PLAIN_GATE));

        assertThat(parsed.scopes().get(0).requiresApproval()).isFalse();
        assertThat(parsed.approvalGates().get(0).timeoutMinutes()).isEqualTo(30);
    }

    @Test
    void newFieldsAreParsedWhenPresent() {
        Manifest parsed = parser.parse(manifest(
                "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\",\"requiresApproval\":true}",
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":5}"));

        assertThat(parsed.scopes().get(0).requiresApproval()).isTrue();
        assertThat(parsed.approvalGates().get(0).timeoutMinutes()).isEqualTo(5);
    }

    @Test
    void schemaAcceptsTheNewFields() {
        var result = validator.validate(manifest(
                "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\",\"requiresApproval\":true}",
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":1440}"));

        assertThat(result.valid()).as(result.errors().toString()).isTrue();
    }

    @Test
    void schemaRejectsBadValues() {
        assertThat(validator.validate(manifest(
                "{\"type\":\"WRITE\",\"resource\":\"r\",\"description\":\"d\",\"requiresApproval\":\"yes\"}", PLAIN_GATE)).valid()).isFalse();
        assertThat(validator.validate(manifest(PLAIN_SCOPE,
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":0}")).valid()).isFalse();
        assertThat(validator.validate(manifest(PLAIN_SCOPE,
                "{\"trigger\":\"g\",\"description\":\"d\",\"timeoutMinutes\":1441}")).valid()).isFalse();
        assertThat(validator.validate(manifest(PLAIN_SCOPE,
                "{\"trigger\":\"" + "x".repeat(101) + "\",\"description\":\"d\"}")).valid()).isFalse();
    }
}
