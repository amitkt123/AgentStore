package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.build.TemplateBuildRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.DataFixtures;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetService;
import com.agentstore.platform.data.RunDatasetPinRepository;
import com.agentstore.platform.execution.FakeSandboxProvider;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxRunStartTest extends AbstractStorageIntegrationTest {

    @Autowired SandboxFixtures fx;
    @Autowired SandboxRunService service;
    @Autowired SandboxProfileService profileService;
    @Autowired RunRepository runs;
    @Autowired SandboxProfileRepository profiles;
    @Autowired FakeSandboxProvider sandbox;
    @Autowired TemplateBuildRepository builds;
    @Autowired DatasetService datasetService;
    @Autowired DatasetBindingRepository bindings;
    @Autowired RunDatasetPinRepository pins;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void startsARunWithNoConnectionAndNoMoney() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");

        long ledgerBefore = jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class);
        long eventsBefore = jdbc.queryForObject("SELECT count(*) FROM usage_event", Long.class);

        Run run = service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);

        assertThat(run.getMode()).isEqualTo(com.agentstore.platform.execution.RunMode.SANDBOX);
        assertThat(run.getConnection()).isNull();
        assertThat(run.getInitiatedByOrgId()).isEqualTo(dev.org().getId());
        assertThat(run.getSandboxProfileId()).isEqualTo(profile.getId());
        assertThat(run.getCostCap()).isEqualByComparingTo("500.00");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(sandbox.createdSpecs()).anyMatch(s -> s.envVars().get("AGENTSTORE_MODE").equals("SANDBOX"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry", Long.class)).isEqualTo(ledgerBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_event", Long.class)).isEqualTo(eventsBefore);
    }

    @Test
    void aProfilesSpendCapOverridesTheManifestsMaxPerRun() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "capped");
        profile.setSpendCap(new BigDecimal("40.00"));
        profiles.save(profile);

        Run run = service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);

        assertThat(run.getCostCap()).isEqualByComparingTo("40.00");
    }

    @Test
    void aDraftAnInReviewAndALiveVersionAreAllRunnable() {
        var dev = fx.developer("valid-tier-a.json");
        fx.verify(dev.org());   // several runs stay open at once here; only concurrency, not version status, is under test
        SandboxProfile profile = fx.profile(dev, "p");
        for (VersionStatus status : VersionStatus.values()) {
            if (status == VersionStatus.REJECTED) {
                continue;
            }
            AgentVersion version = fx.versionOf(dev.agent(), "2." + status.ordinal() + ".0", "valid-tier-a.json", status);
            Run run = service.start(dev.org().getId(), UUID.randomUUID(), version.getId(), profile.getId(), null);
            assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        }
    }

    @Test
    void foreignVersionIs404() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), other.version().getId(), profile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void versionWithNoImageIs409VersionNotBuilt() {
        var dev = fx.developer("valid-tier-a.json");
        AgentVersion version = fx.versionWithNoImage(dev.agent(), "9.9.9", "valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        long before = runs.count();

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), version.getId(), profile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> {
                    assertThat(((SandboxApiException) e).status().value()).isEqualTo(409);
                    assertThat(((SandboxApiException) e).code()).isEqualTo("VERSION_NOT_BUILT");
                });
        assertThat(runs.count()).isEqualTo(before);
    }

    @Test
    void aRebuildEntersPendingBuild() {
        var dev = fx.developer("valid-tier-a.json");
        AgentVersion version = fx.versionNeedingRebuild(dev.agent(), "2.0.0", "valid-tier-a.json", "other-backend");
        SandboxProfile profile = fx.profile(dev, "p");

        Run run = service.start(dev.org().getId(), UUID.randomUUID(), version.getId(), profile.getId(), null);

        assertThat(run.getStatus()).isEqualTo(RunStatus.PENDING_BUILD);
        assertThat(builds.findByAgentVersionIdAndStatus(version.getId(), BuildStatus.QUEUED)).hasSize(1);
    }

    @Test
    void secondConcurrentRunIsRejectedForUnverifiedAndAllowedForVerified() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(429));

        fx.verify(dev.org());
        for (int i = 0; i < 3; i++) {
            service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), fx.profile(dev, "v" + i).getId(), null);
        }
        // 4 open now, verified limit 5: the fifth passes, the sixth does not
        service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), fx.profile(dev, "v4").getId(), null);
        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), fx.profile(dev, "v5").getId(), null))
                .isInstanceOf(SandboxApiException.class);
    }

    @Test
    void concurrentTriggersOnlyOnePasses() throws Exception {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger passed = new AtomicInteger();
        try {
            List<Future<?>> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(pool.submit(() -> {
                    try {
                        start.await();
                        service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);
                        passed.incrementAndGet();
                    } catch (SandboxApiException ignored) {
                        // expected for every loser
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(passed.get()).isEqualTo(1);
        assertThat(runs.countByModeAndInitiatedByOrgIdAndStatusIn(com.agentstore.platform.execution.RunMode.SANDBOX,
                dev.org().getId(), List.of(RunStatus.RUNNING))).isEqualTo(1);
    }

    @Test
    void missingFixtureBindingIs422ThenBoundFixturesResolve() {
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null))
                .isInstanceOf(com.agentstore.platform.data.DataApiException.class);

        Dataset bank = datasetService.upload(dev.org().getId(), UUID.randomUUID(), "a.csv",
                new ByteArrayInputStream(DataFixtures.CSV.getBytes()), "a", null, DatasetPurpose.FIXTURE, null);
        Dataset pdf = datasetService.upload(dev.org().getId(), UUID.randomUUID(), "b.pdf",
                new ByteArrayInputStream(DataFixtures.PDF), "b", null, DatasetPurpose.FIXTURE, null);
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "bank_statement", bank.getId(), "{}", null));
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "statement_pdf", pdf.getId(), "{}", null));

        Run run = service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);

        assertThat(pins.findByIdRunId(run.getId())).extracting(p -> p.getId().getResourceName())
                .containsExactlyInAnyOrder("bank_statement", "statement_pdf");
    }

    @Test
    void inlineProfileIsStoredHiddenAndRolledBackOnFailure() {
        var dev = fx.developer("valid-tier-a.json");

        Run run = service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), null,
                new ProfileInput(null, null, null, null, null, null, null, null, null, null, null, null));

        assertThat(profiles.findById(run.getSandboxProfileId())).get().satisfies(p -> {
            assertThat(p.isInline()).isTrue();
            assertThat(p.getName()).startsWith("inline-");
        });
        assertThat(profileService.list(dev.org().getId(), dev.agent().getId())).isEmpty();

        var other = fx.developer("valid-tier-a.json");
        Dataset foreignFixture = datasetService.upload(other.org().getId(), UUID.randomUUID(), "x.csv",
                new ByteArrayInputStream(DataFixtures.CSV.getBytes()), "x", null, DatasetPurpose.FIXTURE, null);
        var devWithTypedManifest = fx.developer("valid-data-agent.json");
        long profilesBefore = profiles.count();
        long runsBefore = runs.count();

        assertThatThrownBy(() -> service.start(devWithTypedManifest.org().getId(), UUID.randomUUID(),
                devWithTypedManifest.version().getId(), null,
                new ProfileInput(null, null, null, null, null, null, null, null, null,
                        java.util.Map.of("bank_statement", foreignFixture.getId()), null, null)))
                .isInstanceOf(com.agentstore.platform.data.DataApiException.class);
        assertThat(profiles.count()).isEqualTo(profilesBefore);
        assertThat(runs.count()).isEqualTo(runsBefore);
    }

    @Test
    void profileOfAnotherAgentIs404() {
        var dev = fx.developer("valid-tier-a.json");
        var another = fx.agentIn(dev.org(), "valid-tier-a.json");
        SandboxProfile foreignProfile = fx.profile(another, "p");

        assertThatThrownBy(() -> service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), foreignProfile.getId(), null))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).status().value()).isEqualTo(404));
    }

    @Test
    void deletedProfileKeepsTheRunReadableButRerunIs409() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        Run run = service.start(dev.org().getId(), UUID.randomUUID(), dev.version().getId(), profile.getId(), null);
        profileService.delete(dev.org().getId(), dev.agent().getId(), profile.getId());

        Run reloaded = service.owned(dev.org().getId(), run.getId());
        assertThat(reloaded.getSandboxProfileId()).isNull();

        assertThatThrownBy(() -> service.rerun(dev.org().getId(), UUID.randomUUID(), run.getId()))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).code()).isEqualTo("PROFILE_DELETED"));
    }

}
