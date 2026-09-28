package com.agentstore.platform.verification;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class DeveloperVerificationRepositoryTest extends AbstractStorageIntegrationTest {

    @Autowired DeveloperVerificationFixtures fx;
    @Autowired DeveloperVerificationRepository repo;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void findByStatusReturnsOnlyPendingSubmissions() throws Exception {
        var pending = fx.developer();
        fx.pendingSubmission(pending);
        var untouched = fx.developer();

        assertThat(repo.findByOrgId(untouched.getId())).isEmpty();

        var results = repo.findByStatus(VerificationStatus.PENDING);

        assertThat(results).extracting(DeveloperVerification::getOrgId).contains(pending.getId());
        assertThat(results).extracting(DeveloperVerification::getOrgId).doesNotContain(untouched.getId());
    }

    @Test
    void findByIdForUpdateLocksARowByItsOwnId() throws Exception {
        var org = fx.developer();
        var verification = fx.pendingSubmission(org);

        var locked = new TransactionTemplate(txManager).execute(status -> repo.findByIdForUpdate(verification.getId()));

        assertThat(locked).isPresent();
        assertThat(locked.get().getStatus()).isEqualTo(VerificationStatus.PENDING);
    }
}
