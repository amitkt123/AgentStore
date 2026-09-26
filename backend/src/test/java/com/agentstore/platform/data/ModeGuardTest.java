package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.manifest.ManifestParser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModeGuardTest extends AbstractStorageIntegrationTest {

    @Autowired
    DataFixtures fx;
    @Autowired
    BindingResolver resolver;
    @Autowired
    ManifestParser parser;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    DatasetBindingRepository bindingRepository;

    @Test
    void theRulesTable() {
        assertThat(DataAccessRules.allows(RunMode.PRODUCTION, DatasetPurpose.BUYER_DATA)).isTrue();
        for (DatasetPurpose purpose : new DatasetPurpose[]{DatasetPurpose.FIXTURE, DatasetPurpose.SAMPLE, DatasetPurpose.EVAL}) {
            assertThat(DataAccessRules.allows(RunMode.PRODUCTION, purpose)).isFalse();
            for (RunMode mode : new RunMode[]{RunMode.SANDBOX, RunMode.REVIEW, RunMode.TRIAL}) {
                assertThat(DataAccessRules.allows(mode, purpose)).isTrue();
            }
        }
        for (RunMode mode : new RunMode[]{RunMode.SANDBOX, RunMode.REVIEW, RunMode.TRIAL}) {
            assertThat(DataAccessRules.allows(mode, DatasetPurpose.BUYER_DATA)).isFalse();
        }
    }

    @Test
    void aSandboxRunNeverResolvesBuyerData() {
        var s = fx.scenario();
        var manifest = parser.parse(s.version().getManifestJson());
        fx.bind(s.connection(), "bank_statement", fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV));
        fx.bind(s.connection(), "statement_pdf", fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF));

        assertThat(resolver.resolve(s.connection(), manifest, RunMode.PRODUCTION)).hasSize(2);
        for (RunMode mode : new RunMode[]{RunMode.SANDBOX, RunMode.REVIEW, RunMode.TRIAL}) {
            assertThatThrownBy(() -> resolver.resolve(s.connection(), manifest, mode))
                    .isInstanceOf(DataApiException.class).hasMessageContaining("Bind a file");
        }
    }

    @Test
    void aFixtureDatasetResolvesOnlyForNonProductionRuns() {
        var s = fx.scenario();
        var manifest = parser.parse(s.version().getManifestJson());
        Dataset csv = fx.upload(s.buyer(), "jan.csv", DataFixtures.CSV);
        Dataset pdf = fx.upload(s.buyer(), "jan.pdf", DataFixtures.PDF);
        fx.bind(s.connection(), "bank_statement", csv);
        fx.bind(s.connection(), "statement_pdf", pdf);
        // WS6 will create FIXTURE datasets; simulate that by changing the purpose of the two bound files
        for (Dataset dataset : List.of(csv, pdf)) {
            dataset.setPurpose(DatasetPurpose.FIXTURE);
            datasetRepository.save(dataset);
        }

        assertThat(resolver.resolve(s.connection(), manifest, RunMode.SANDBOX))
                .extracting(BindingResolver.ResolvedPin::resource).containsExactlyInAnyOrder("bank_statement", "statement_pdf");
        assertThatThrownBy(() -> resolver.resolve(s.connection(), manifest, RunMode.PRODUCTION))
                .isInstanceOf(DataApiException.class).hasMessageContaining("Bind a file");
    }
}
