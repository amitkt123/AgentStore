package com.agentstore.platform.data;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.manifest.DataFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataModelTest extends AbstractIntegrationTest {

    @Autowired
    OrgRepository orgRepository;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    DatasetBindingRepository bindingRepository;

    Dataset dataset(UUID orgId) {
        Dataset d = new Dataset();
        d.setOwnerOrgId(orgId);
        d.setPurpose(DatasetPurpose.BUYER_DATA);
        d.setDisplayName("HDFC Jan");
        d.setFormat(DataFormat.CSV);
        d.setStorageKey("datasets/x/original");
        d.setRecordsKey("datasets/x/records.jsonl");
        d.setSha256("a".repeat(64));
        d.setSizeBytes(100);
        d.setRecordsSizeBytes(150);
        d.setRowCount(2L);
        d.setColumns(List.of("date", "amount"));
        return datasetRepository.save(d);
    }

    @Test
    void anOrgDefaultsToFiveGibOfStorage() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));

        assertThat(orgRepository.findById(org.getId()).orElseThrow().getStorageLimitBytes())
                .isEqualTo(5L * 1024 * 1024 * 1024);
    }

    @Test
    void aDatasetRoundTripsAndUsageCountsOriginalPlusRecords() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
        Dataset saved = dataset(org.getId());

        Dataset loaded = datasetRepository.findByIdAndOwnerOrgId(saved.getId(), org.getId()).orElseThrow();
        assertThat(loaded.columns()).containsExactly("date", "amount");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(datasetRepository.usedBytes(org.getId())).isEqualTo(250);
        assertThat(datasetRepository.findByIdAndOwnerOrgId(saved.getId(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void aResourceCanBeBoundToOnlyOneDatasetPerOwner() {
        Org org = orgRepository.save(new Org(null, "Acme " + UUID.randomUUID(), OrgType.BUYER));
        Dataset a = dataset(org.getId());
        Dataset b = dataset(org.getId());
        UUID connection = UUID.randomUUID();
        bindingRepository.saveAndFlush(new DatasetBinding(BindingOwnerType.CONNECTION, connection, "bank_statement",
                a.getId(), "{}", UUID.randomUUID()));

        assertThatThrownBy(() -> bindingRepository.saveAndFlush(new DatasetBinding(BindingOwnerType.CONNECTION,
                connection, "bank_statement", b.getId(), "{}", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(bindingRepository.existsByDatasetId(a.getId())).isTrue();
        assertThat(bindingRepository.findByDatasetIdIn(List.of(a.getId(), b.getId()))).hasSize(1);
    }
}
