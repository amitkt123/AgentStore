package com.agentstore.platform.build;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.*;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BuildSchemaTest extends AbstractIntegrationTest {

    @Autowired OrgRepository orgs;
    @Autowired UserRepository users;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired AgentBundleRepository bundles;
    @Autowired TemplateBuildRepository builds;

    @Test
    void bundlesBuildsAndVersionsWithoutAnImageRoundTrip() {
        Org org = orgs.save(new Org(null, "SchemaCo", OrgType.DEVELOPER));
        User user = users.save(new User(null, "schema-" + UUID.randomUUID() + "@example.com", null, "Dev"));
        Agent agent = agents.save(new Agent(null, org, "Recon", "finance", "d"));
        UUID bundleId = UUID.randomUUID();
        AgentBundle bundle = bundles.save(new AgentBundle(bundleId, agent, user.getId(),
                "bundles/" + agent.getId() + "/" + bundleId + ".zip", "a".repeat(64), 1234, BundleStatus.UPLOADED));

        AgentVersion version = new AgentVersion(null, agent, "1.0.0", "{}", null, VersionStatus.DRAFT, null, null);
        version.setBundle(bundle);
        version.setBuildStatus(BuildStatus.QUEUED);
        version = versions.save(version);

        TemplateBuild build = builds.save(new TemplateBuild(version, bundle, "e2b", "python-3.12", "agent/main.py"));

        TemplateBuild loaded = builds.findById(build.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(BuildStatus.QUEUED);
        assertThat(loaded.getAttempts()).isZero();
        assertThat(loaded.getQueuedAt()).isNotNull();
        assertThat(loaded.getBundle().getId()).isEqualTo(bundleId);
        assertThat(versions.findById(version.getId()).orElseThrow().getImageRef()).isNull();
        assertThat(versions.findByBundleId(bundleId)).isPresent();
        assertThat(versions.findByAgentIdAndVersionNumber(agent.getId(), "1.0.0")).isPresent();
        assertThat(builds.existsByAgentVersionIdAndBackendAndStatusIn(version.getId(), "e2b", List.of(BuildStatus.QUEUED)))
                .isTrue();
    }

    @Test
    void pendingBuildIsNotTerminal() {
        assertThat(RunStatus.PENDING_BUILD.isTerminal()).isFalse();
    }
}
