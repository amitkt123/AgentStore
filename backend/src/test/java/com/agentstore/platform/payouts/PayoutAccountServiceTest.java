package com.agentstore.platform.payouts;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.verification.DeveloperVerification;
import com.agentstore.platform.verification.DeveloperVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The late-completion contract of applyValidation, relied on by the webhook and the poll. */
class PayoutAccountServiceTest extends AbstractIntegrationTest {

    @Autowired
    PayoutAccountService service;
    @Autowired
    PayoutAccountRepository accounts;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    DeveloperVerificationRepository verifications;
    @Autowired
    PayoutProvider provider;

    Org org;

    @BeforeEach
    void setUp() {
        ((FakePayoutProvider) provider).reset();
        org = new Org(null, "SvcDev-" + UUID.randomUUID(), OrgType.DEVELOPER);
        org.setDeveloperVerifiedAt(Instant.now());
        org = orgRepository.save(org);
        DeveloperVerification v = new DeveloperVerification(org.getId());
        v.setLegalName("Amit Tiwari");
        verifications.save(v);
    }

    PayoutAccountView pending(String number) {
        var v = service.add(org.getId(), UUID.randomUUID(),
                new PayoutAccountRequest(PayoutAccountType.BANK, number, "HDFC0001234", null, "Amit Tiwari"));
        assertThat(v.status()).isEqualTo("PENDING");
        return v;
    }

    static PayoutProvider.ProviderValidation completed(String name) {
        return new PayoutProvider.ProviderValidation("v1", "COMPLETED", name, null);
    }

    @Test
    void lateCompletionWithMatchingNameVerifies() {
        var v = pending("123456781111");
        service.applyValidation(v.id(), completed("AMIT TIWARI"));
        PayoutAccount a = accounts.findById(v.id()).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(PayoutAccountStatus.VERIFIED);
        assertThat(a.getVerifiedAt()).isNotNull();
        assertThat(a.getNameMatch()).isTrue();
        assertThat(service.activeAccount(org.getId())).map(PayoutAccount::getId).contains(v.id());
    }

    @Test
    void lateCompletionWithMismatchedNameFails() {
        var v = pending("123456781111");
        service.applyValidation(v.id(), completed("SOMEONE ELSE"));
        PayoutAccount a = accounts.findById(v.id()).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(PayoutAccountStatus.FAILED);
        assertThat(a.getFailureReason()).isEqualTo("NAME_MISMATCH");
        assertThat(a.getNameMatch()).isFalse();
        assertThat(a.getVerifiedAt()).isNull();
    }

    @Test
    void repeatOnFinalRowIsANoOp() {
        var v = pending("123456781111");
        service.applyValidation(v.id(), completed("AMIT TIWARI"));
        service.applyValidation(v.id(), completed("SOMEONE ELSE"));
        service.applyValidation(v.id(), new PayoutProvider.ProviderValidation("v1", "FAILED", null, "late"));
        PayoutAccount a = accounts.findById(v.id()).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(PayoutAccountStatus.VERIFIED);
        assertThat(a.getValidatedName()).isEqualTo("AMIT TIWARI");
        assertThat(a.getFailureReason()).isNull();
    }

    @Test
    void failedWithoutReasonGetsDefaultAndPendingChangesNothing() {
        var v = pending("123456781111");
        service.applyValidation(v.id(), new PayoutProvider.ProviderValidation("v1", "PENDING", null, null));
        assertThat(accounts.findById(v.id()).orElseThrow().getStatus()).isEqualTo(PayoutAccountStatus.PENDING);
        service.applyValidation(v.id(), new PayoutProvider.ProviderValidation("v1", "FAILED", null, null));
        PayoutAccount a = accounts.findById(v.id()).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(PayoutAccountStatus.FAILED);
        assertThat(a.getFailureReason()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void replacedPendingRowIsResolvedButNeverBecomesActive() {
        var old = pending("123456781111");
        var current = service.add(org.getId(), UUID.randomUUID(),
                new PayoutAccountRequest(PayoutAccountType.BANK, "123456789012", "HDFC0001234", null, "Amit Tiwari"));
        assertThat(current.status()).isEqualTo("VERIFIED");

        service.applyValidation(old.id(), completed("AMIT TIWARI"));

        PayoutAccount a = accounts.findById(old.id()).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(PayoutAccountStatus.VERIFIED);
        assertThat(a.getReplacedAt()).isNotNull();
        assertThat(service.activeAccount(org.getId())).map(PayoutAccount::getId).contains(current.id());
    }
}
