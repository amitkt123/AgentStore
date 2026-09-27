package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.BindingResolver;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.data.DatasetService;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxBindingResolverTest extends AbstractStorageIntegrationTest {

    static final String CSV = "date,narration,amount\n2026-01-05,Salary,50000.00\n";

    @Autowired SandboxFixtures fx;
    @Autowired BindingResolver resolver;
    @Autowired ManifestParser parser;
    @Autowired DatasetService datasetService;
    @Autowired DatasetBindingRepository bindings;
    @Autowired DatasetRepository datasets;

    Dataset fixture(SandboxFixtures.Dev dev, String name) {
        return datasetService.upload(dev.org().getId(), UUID.randomUUID(), name, new ByteArrayInputStream(CSV.getBytes()),
                name, null, DatasetPurpose.FIXTURE, null);
    }

    void bind(SandboxProfile profile, String resource, Dataset dataset) {
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), resource, dataset.getId(), "{}", null));
    }

    @Test
    void everyBoundReadResourceResolves() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Manifest manifest = parser.parse(dev.version().getManifestJson());
        bind(profile, "bank_statement", fixture(dev, "a.csv"));
        bind(profile, "statement_pdf", fixture(dev, "b.csv"));

        var pins = resolver.resolveForProfile(profile.getId(), manifest, RunMode.SANDBOX);

        assertThat(pins).extracting(BindingResolver.ResolvedPin::resource).containsExactlyInAnyOrder("bank_statement", "statement_pdf");
    }

    @Test
    void aMissingBindingIsReportedByName() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Manifest manifest = parser.parse(dev.version().getManifestJson());
        bind(profile, "bank_statement", fixture(dev, "a.csv"));

        assertThatThrownBy(() -> resolver.resolveForProfile(profile.getId(), manifest, RunMode.SANDBOX))
                .hasMessageContaining("statement_pdf");
    }

    @Test
    void anUntypedManifestWithNothingBoundResolvesToNothing() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Manifest manifest = parser.parse(dev.version().getManifestJson());

        assertThat(resolver.resolveForProfile(profile.getId(), manifest, RunMode.SANDBOX)).isEmpty();
    }

    @Test
    void aDatasetRepurposedAsBuyerDataIsTreatedAsUnbound() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Manifest manifest = parser.parse(dev.version().getManifestJson());
        Dataset dataset = fixture(dev, "a.csv");
        bind(profile, "bank_statement", dataset);
        bind(profile, "statement_pdf", fixture(dev, "b.csv"));
        dataset.setPurpose(DatasetPurpose.BUYER_DATA);
        datasets.save(dataset);

        assertThatThrownBy(() -> resolver.resolveForProfile(profile.getId(), manifest, RunMode.SANDBOX))
                .hasMessageContaining("bank_statement");
    }
}
