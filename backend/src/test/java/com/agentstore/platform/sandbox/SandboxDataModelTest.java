package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxDataModelTest extends AbstractIntegrationTest {

    @Autowired SandboxFixtures fx;
    @Autowired RunRepository runs;
    @Autowired SandboxProfileRepository profiles;
    @Autowired com.agentstore.platform.identity.UserRepository users;

    @Test
    void aSandboxRunHasNoConnectionAndKeepsItsSandboxFields() {
        var dev = fx.developer("valid-tier-a.json");
        var profile = fx.profile(dev, "default");
        Run saved = fx.sandboxRun(dev, profile, RunStatus.RUNNING);

        Run loaded = runs.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getConnection()).isNull();
        assertThat(loaded.getMode()).isEqualTo(RunMode.SANDBOX);
        assertThat(loaded.getInitiatedByOrgId()).isEqualTo(dev.org().getId());
        assertThat(loaded.getSandboxProfileId()).isEqualTo(profile.getId());
        assertThat(loaded.getSimulatedCharged()).isEqualByComparingTo("0");
        assertThat(loaded.getRequestedCharge()).isEqualByComparingTo("0");
    }

    @Test
    void theDatabaseRejectsARunWithNoOwner() {
        var dev = fx.developer("valid-tier-a.json");
        Run productionWithoutConnection = new Run(null, null, dev.version(), RunStatus.RUNNING, new BigDecimal("5"),
                BigDecimal.ZERO, null, RunTokenService.hash("t2"));
        assertThatThrownBy(() -> runs.saveAndFlush(productionWithoutConnection)).isInstanceOf(DataIntegrityViolationException.class);

        Run sandboxWithoutInitiator = new Run(null, null, dev.version(), RunStatus.RUNNING, new BigDecimal("5"),
                BigDecimal.ZERO, null, RunTokenService.hash("t3"));
        sandboxWithoutInitiator.setMode(RunMode.SANDBOX);
        assertThatThrownBy(() -> runs.saveAndFlush(sandboxWithoutInitiator)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void profileNamesAreUniquePerAgentAndDefaultsAreSafe() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = profiles.findById(fx.profile(dev, "happy-path").getId()).orElseThrow();

        assertThat(profile.getGatePolicy()).contains("default").contains("MANUAL").doesNotContain("AUTO");
        assertThat(profile.getInputJson()).isEqualTo("{}");
        assertThat(profile.isInline()).isFalse();
        assertThat(profile.getLlmMode()).isNull();

        SandboxProfile dup = new SandboxProfile(dev.agent().getId(), "happy-path");
        assertThatThrownBy(() -> profiles.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newProfileFieldsRoundTripWithPortalDefaults() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p1");

        assertThat(profile.getSource()).isEqualTo(ProfileSource.PORTAL);
        assertThat(profile.isTrialEnabled()).isFalse();
        assertThat(profile.getDescription()).isNull();
        assertThat(profile.getNoRealDataAttestedBy()).isNull();
        assertThat(profile.getRepoSourceHash()).isNull();

        var attester = users.save(new com.agentstore.platform.identity.User(null,
                "attester-" + UUID.randomUUID() + "@example.com", null, "Attester"));
        profile.setDescription("Clean invoice batch");
        profile.setTrialEnabled(true);
        profile.setNoRealDataAttestedBy(attester.getId());
        profile.setNoRealDataAttestedAt(Instant.now());
        profile.setSource(ProfileSource.REPO);
        profile.setRepoSourceHash("abc123");
        SandboxProfile reloaded = profiles.saveAndFlush(profile);

        assertThat(reloaded.getDescription()).isEqualTo("Clean invoice batch");
        assertThat(reloaded.isTrialEnabled()).isTrue();
        assertThat(reloaded.getSource()).isEqualTo(ProfileSource.REPO);
        assertThat(reloaded.getRepoSourceHash()).isEqualTo("abc123");
    }
}
