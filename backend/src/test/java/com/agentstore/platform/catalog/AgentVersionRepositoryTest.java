package com.agentstore.platform.catalog;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentVersionRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    AgentVersionRepository agentVersionRepository;

    @Test
    void findsMostRecentLiveVersionForAnAgent() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "desc"));

        AgentVersion v1 = new AgentVersion(null, agent, "1.0.0", "{}", "template-v1",
                VersionStatus.LIVE, Tier.TIER_A, "first release");
        v1.setApprovedAt(Instant.now().minusSeconds(3600));
        agentVersionRepository.save(v1);

        AgentVersion v2 = new AgentVersion(null, agent, "1.1.0", "{}", "template-v2",
                VersionStatus.LIVE, Tier.TIER_A, "second release");
        v2.setApprovedAt(Instant.now());
        agentVersionRepository.save(v2);

        AgentVersion draft = new AgentVersion(null, agent, "1.2.0", "{}", "template-v3",
                VersionStatus.DRAFT, null, "unreleased");
        agentVersionRepository.save(draft);

        var current = agentVersionRepository.findFirstByAgentIdAndStatusOrderByApprovedAtDesc(
                agent.getId(), VersionStatus.LIVE);

        assertThat(current).isPresent();
        assertThat(current.get().getVersionNumber()).isEqualTo("1.1.0");
    }
}
