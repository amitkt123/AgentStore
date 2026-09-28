package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ManualKycProviderTest extends AbstractIntegrationTest {

    @Autowired ManualKycProvider provider;
    @Autowired VerificationCheckRepository checks;
    @Autowired DeveloperVerificationRepository verifications;
    @Autowired OrgRepository orgs;
    @Autowired UserRepository users;

    DeveloperVerification verification() {
        Org org = orgs.save(new Org(null, "DevCo-" + UUID.randomUUID(), OrgType.DEVELOPER));
        return verifications.save(new DeveloperVerification(org.getId()));
    }

    UUID adminId() {
        return users.save(new User(null, "admin-" + UUID.randomUUID() + "@example.com", null, "Admin")).getId();
    }

    @Test
    void checkPersistsAManualVerdictWithAllFields() {
        var v = verification();
        UUID adminId = adminId();

        VerificationCheck result = provider.check(v.getId(), CheckType.PAN, true, "matches document", adminId);

        assertThat(result.getId()).isNotNull();
        assertThat(result.getVerificationId()).isEqualTo(v.getId());
        assertThat(result.getCheckType()).isEqualTo(CheckType.PAN);
        assertThat(result.getProvider()).isEqualTo(CheckProvider.MANUAL);
        assertThat(result.isPassed()).isTrue();
        assertThat(result.getNote()).isEqualTo("matches document");
        assertThat(result.getCheckedByUserId()).isEqualTo(adminId);
        assertThat(result.getCheckedAt()).isNotNull();
    }

    @Test
    void repeatedChecksForTheSameTypeAreKeptNotOverwrittenNewestFirst() throws InterruptedException {
        var v = verification();

        provider.check(v.getId(), CheckType.PAN, false, "blurry image", adminId());
        Thread.sleep(5);
        provider.check(v.getId(), CheckType.PAN, true, "resubmitted, clear now", adminId());

        List<VerificationCheck> history = checks.findByVerificationIdOrderByCheckedAtDesc(v.getId());

        assertThat(history).hasSize(2);
        assertThat(history.get(0).isPassed()).isTrue();
        assertThat(history.get(1).isPassed()).isFalse();
    }
}
