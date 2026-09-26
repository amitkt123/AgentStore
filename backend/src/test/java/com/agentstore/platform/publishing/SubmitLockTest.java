package com.agentstore.platform.publishing;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildFixtures;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Submit serializes with bundle finalize on the agent row. Without that, a submit that read a built version before a
 * finalize committed would merge the old bundle and image back and publish it, discarding an accepted push.
 */
class SubmitLockTest extends AbstractStorageIntegrationTest {

    @Autowired BuildFixtures fixtures;
    @Autowired PublishingService publishing;
    @Autowired AgentRepository agents;
    @Autowired AgentVersionRepository versions;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void aSubmitWaitsForAConcurrentFinalizeAndThenSeesTheNewPush() throws Exception {
        var built = fixtures.queued("e2b");
        AgentVersion version = fixtures.reload(built.version());
        version.setBuildStatus(BuildStatus.SUCCEEDED);
        version.setImageRef("tpl-old");
        versions.save(version);

        CompletableFuture<AgentVersion> submit = new TransactionTemplate(transactionManager).execute(status -> {
            // What finalize does while holding the agent row: a new push resets the version to QUEUED with no image.
            agents.findByIdForUpdate(built.agent().getId()).orElseThrow();
            CompletableFuture<AgentVersion> f = CompletableFuture.supplyAsync(
                    () -> publishing.submit(built.version().getId(), built.org().getId()));
            assertThatThrownBy(() -> f.get(700, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            AgentVersion pushed = versions.findById(built.version().getId()).orElseThrow();
            pushed.setImageRef(null);
            pushed.setBuildStatus(BuildStatus.QUEUED);
            versions.save(pushed);
            return f;
        });

        assertThatThrownBy(() -> submit.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        AgentVersion after = fixtures.reload(built.version());
        assertThat(after.getStatus()).isEqualTo(VersionStatus.DRAFT);
        assertThat(after.getBuildStatus()).isEqualTo(BuildStatus.QUEUED);
        assertThat(after.getImageRef()).isNull();
    }
}
