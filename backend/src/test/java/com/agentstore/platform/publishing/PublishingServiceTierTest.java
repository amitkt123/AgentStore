package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PublishingServiceTierTest extends AbstractIntegrationTest {

    @Autowired
    PublishingService publishingService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    @Test
    void tierAManifestAutoApprovesToLive() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-a.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));

        AgentVersion result = publishingService.submit(version.getId(), developer.getId());

        assertThat(result.getTier()).isEqualTo(Tier.TIER_A);
        assertThat(result.getStatus()).isEqualTo(VersionStatus.LIVE);
        assertThat(result.getApprovedAt()).isNotNull();
    }

    @Test
    void tierBManifestStaysInReviewForManualApproval() throws Exception {
        Org developer = orgRepository.save(new Org(null, "DevCo2", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Collections", "collections", "d"));
        String manifest = Files.readString(Path.of("src/test/resources/manifest/valid-tier-b.json"));
        AgentVersion version = agentVersionRepository.save(new AgentVersion(null, agent, "1.0.0", manifest,
                "template-v1", VersionStatus.DRAFT, null, "cl"));

        AgentVersion result = publishingService.submit(version.getId(), developer.getId());

        assertThat(result.getTier()).isEqualTo(Tier.TIER_B);
        assertThat(result.getStatus()).isEqualTo(VersionStatus.IN_REVIEW);
        assertThat(result.getApprovedAt()).isNull();
    }
}
