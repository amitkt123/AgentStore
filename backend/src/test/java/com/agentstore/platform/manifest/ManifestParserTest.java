package com.agentstore.platform.manifest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestParserTest {

    ManifestParser parser = new ManifestParser();

    String read(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/manifest/" + name));
    }

    @Test
    void parsesTierAManifestFields() throws Exception {
        Manifest manifest = parser.parse(read("valid-tier-a.json"));

        assertThat(manifest.name()).isEqualTo("Bank Reconciliation Agent");
        assertThat(manifest.scopes()).hasSize(1);
        assertThat(manifest.scopes().get(0).type()).isEqualTo(ScopeType.READ);
        assertThat(manifest.scopes().get(0).resource()).isEqualTo("bank_statement");
        assertThat(manifest.costEnvelope().pricingUnit()).isEqualTo(PricingUnit.METERED);
        assertThat(manifest.costEnvelope().unitPrice()).isEqualByComparingTo(new BigDecimal("2.00"));
        assertThat(manifest.approvalGates()).isEmpty();
    }

    @Test
    void parsesTierBManifestWithApprovalGate() throws Exception {
        Manifest manifest = parser.parse(read("valid-tier-b.json"));

        assertThat(manifest.scopes()).extracting(Scope::type).contains(ScopeType.WRITE);
        assertThat(manifest.networkAllowlist()).containsExactly("graph.facebook.com");
        assertThat(manifest.approvalGates()).hasSize(1);
        assertThat(manifest.approvalGates().get(0).trigger()).isEqualTo("MESSAGE_CUSTOMER");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatIllegalArgument(() -> parser.parse("{broken"));
    }

    private void assertThatIllegalArgument(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }
}
