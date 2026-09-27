package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetPiiScanTest extends AbstractStorageIntegrationTest {

    @Autowired DatasetService datasetService;
    @Autowired OrgRepository orgs;

    private Org org() {
        return orgs.save(new Org(null, "Org-" + UUID.randomUUID(), OrgType.DEVELOPER));
    }

    @Test
    void aFixtureCsvWithPiiIsScannedAndFlagged() {
        String csv = "name,email\nA,person@example.com\n";
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "f.csv", null,
                DatasetPurpose.FIXTURE, null);

        assertThat(dataset.piiScan()).isNotNull();
        assertThat(dataset.piiScan().status()).isEqualTo(PiiScanner.Result.FOUND);
        assertThat(dataset.piiClean()).isFalse();
    }

    @Test
    void aCleanFixtureCsvIsScannedClean() {
        String csv = "name,amount\nA,100\n";
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "f.csv", null,
                DatasetPurpose.FIXTURE, null);

        assertThat(dataset.piiScan().status()).isEqualTo(PiiScanner.Result.CLEAN);
        assertThat(dataset.piiClean()).isTrue();
    }

    @Test
    void buyerDataIsNeverScanned() {
        String csv = "name,email\nA,person@example.com\n";
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.csv",
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "f.csv", null);

        assertThat(dataset.piiScan()).isNull();
        assertThat(dataset.piiClean()).isFalse();
    }

    @Test
    void aPdfFixtureIsNeverScannedAndCanNeverBeClean() {
        Dataset dataset = datasetService.upload(org().getId(), UUID.randomUUID(), "f.pdf",
                new ByteArrayInputStream(DataFixtures.PDF), "f.pdf", null, DatasetPurpose.FIXTURE, null);

        assertThat(dataset.piiScan()).isNull();
        assertThat(dataset.piiClean()).isFalse();
    }
}
