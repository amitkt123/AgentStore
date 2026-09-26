package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildFixtures;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitBuildGateTest extends AbstractStorageIntegrationTest {

    @Autowired BuildFixtures fixtures;
    @Autowired PublishingService publishing;
    @Autowired AgentVersionRepository versions;

    @Test
    void aVersionWhoseBuildHasNotSucceededCannotBeSubmitted() {
        var built = fixtures.building("e2b", java.time.Instant.now(), 1);
        assertThatThrownBy(() -> publishing.submit(built.version().getId(), built.org().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("build has not succeeded");
    }

    @Test
    void aVersionWithASucceededBuildCanBeSubmitted() {
        var built = fixtures.queued("e2b");
        AgentVersion version = fixtures.reload(built.version());
        version.setBuildStatus(BuildStatus.SUCCEEDED);
        version.setImageRef("tpl-1");
        versions.save(version);
        assertThat(publishing.submit(version.getId(), built.org().getId()).getStatus()).isEqualTo(VersionStatus.LIVE);
    }

    @Test
    void aVersionWithNoImageAtAllCannotBeSubmitted() {
        var built = fixtures.queued("e2b");
        AgentVersion version = fixtures.reload(built.version());
        version.setBundle(null);
        version.setBuildStatus(null);
        versions.save(version);
        assertThatThrownBy(() -> publishing.submit(version.getId(), built.org().getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no image");
    }
}
