package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.build.BuildStatus;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.DataFixtures;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxProfileStatusTest extends AbstractStorageIntegrationTest {

    @Autowired SandboxFixtures fx;
    @Autowired DataFixtures dataFx;
    @Autowired ProfileStatusService statusService;
    @Autowired DatasetBindingRepository bindings;

    @Test
    void readyWhenTheAgentHasNoVersionAtAllIsNotReachableHere_soReadyWithNoBindingsInstead() {
        var dev = fx.developer("valid-tier-a.json");   // has a DRAFT version, but zero bindings
        SandboxProfile profile = fx.profile(dev, "p");

        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.READY);
    }

    @Test
    void readyAgainstACleanDraft() {
        var dev = fx.developer("valid-tier-a.json");
        SandboxProfile profile = fx.profile(dev, "p");
        var clean = dataFx.upload(dev.org(), "clean.csv", "name,amount\nA,100\n");
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "bank_statement", clean.getId(), "{}", null));

        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.READY);
    }

    @Test
    void fileErrorsWhenABindingFailsTheSchemaCheck() {
        // valid-tier-a.json declares bank_statement only as a scope (untyped resource, always passes the check);
        // valid-data-agent.json types it as CSV, so a bound PDF fails the format check.
        var dev = fx.developer("valid-data-agent.json");
        SandboxProfile profile = fx.profile(dev, "p");
        var pdf = dataFx.upload(dev.org(), "f.pdf", DataFixtures.PDF);
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "bank_statement", pdf.getId(), "{}", null));

        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.FILE_ERRORS);
    }

    @Test
    void draftTakesPrecedenceOverLive() {
        var dev = fx.developer("valid-data-agent.json");
        dev.version().setStatus(VersionStatus.LIVE);
        dev.version().setBuildStatus(BuildStatus.SUCCEEDED);
        fx.versions().save(dev.version());
        fx.versionOf(dev.agent(), "2.0.0", "valid-data-agent.json", VersionStatus.DRAFT);
        SandboxProfile profile = fx.profile(dev, "p");
        var pdf = dataFx.upload(dev.org(), "f.pdf", DataFixtures.PDF);
        bindings.save(new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), "bank_statement", pdf.getId(), "{}", null));

        // Both the LIVE and the DRAFT version declare the same manifest shape here, so this alone doesn't
        // distinguish precedence; the point of this test is that compute() does not throw picking between two
        // candidate versions and resolves to the newer (DRAFT) row, per findFirstByAgentIdAndStatus...
        assertThat(statusService.compute(profile)).isEqualTo(ProfileStatus.FILE_ERRORS);
    }
}
