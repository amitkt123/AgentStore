package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.IdentityFixtures;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateDecisionCheckTest extends AbstractIntegrationTest {

    @Autowired ExecutionFixtures fixtures;
    @Autowired IdentityFixtures identities;
    @Autowired GateService gateService;
    @Autowired GateRequestRepository gates;
    @Autowired OrgMembershipRepository memberships;

    private ExecutionFixtures.Scenario scenario() {
        return fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
    }

    private GateRequest open(ExecutionFixtures.Scenario s, String token) {
        Run run = fixtures.run(s, RunStatus.RUNNING, "sbx-" + token, token);
        return gateService.open(run.getId(), "post_journal_entries", 5, "Post", "{}", "[]");
    }

    private void assertNotAnApprover(Runnable decide) {
        assertThatThrownBy(decide::run).isInstanceOfSatisfying(NotAnApproverException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getReason()).contains("approver");
        }).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anApproverOnTheSnapshotCanDecideAndTheChannelIsRecorded() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        GateRequest gate = open(s, "tok-gd-1");

        gateService.decide(gate.getRun().getId(), gate.getId(), true, approver, null, DecisionChannel.EMAIL_LINK);

        assertThat(gates.findById(gate.getId()).orElseThrow().getDecidedVia()).isEqualTo(DecidedVia.EMAIL_LINK);
    }

    @Test
    void theSlackChannelMapsToSlackLink() {
        var s = scenario();
        UUID approver = fixtures.approver(s).getId();
        GateRequest gate = open(s, "tok-gd-2");

        gateService.decide(gate.getRun().getId(), gate.getId(), false, approver, "no", DecisionChannel.SLACK_LINK);

        assertThat(gates.findById(gate.getId()).orElseThrow().getDecidedVia()).isEqualTo(DecidedVia.SLACK_LINK);
    }

    @Test
    void aMemberRemovedAfterTheGateOpenedIsRefusedEvenThoughStillOnTheSnapshot() {
        var s = scenario();
        UUID removed = fixtures.approver(s).getId();
        fixtures.approver(s);   // a second approver so the snapshot stays restricted
        GateRequest gate = open(s, "tok-gd-3");
        assertThat(gate.eligibleApproverIds()).contains(removed);
        memberships.deleteById(new OrgMembershipId(removed, s.buyer().getId()));

        assertNotAnApprover(() -> gateService.decide(gate.getRun().getId(), gate.getId(), true, removed, null,
                DecisionChannel.WEB));
        assertThat(gates.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.PENDING);
    }

    @Test
    void aBuyerAdminOnTheSnapshotCanDecide() {
        var s = scenario();
        fixtures.approver(s);
        var admin = identities.member(s.buyer(), "admin-" + UUID.randomUUID() + "@example.com",
                UserRole.BUYER_ADMIN, "Finance lead");
        GateRequest gate = open(s, "tok-gd-4");
        assertThat(gate.eligibleApproverIds()).contains(admin.getUser().getId());

        gateService.decide(gate.getRun().getId(), gate.getId(), true, admin.getUser().getId(), null, DecisionChannel.WEB);

        assertThat(gates.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);
    }

    @Test
    void aMemberPromotedToAdminAfterTheGateOpenedCanStillDecide() {
        var s = scenario();
        var membership = identities.member(s.buyer(), "promoted-" + UUID.randomUUID() + "@example.com",
                UserRole.BUYER_MEMBER, "Approver");
        GateRequest gate = open(s, "tok-gd-8");
        membership.setRole(UserRole.BUYER_ADMIN);
        memberships.save(membership);

        gateService.decide(gate.getRun().getId(), gate.getId(), true, membership.getUser().getId(), null, DecisionChannel.WEB);

        assertThat(gates.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);
    }

    @Test
    void aMemberOfAnotherOrgIsRefused() {
        var s = scenario();
        fixtures.approver(s);
        var other = scenario();
        UUID outsider = fixtures.approver(other).getId();
        GateRequest gate = open(s, "tok-gd-5");

        assertNotAnApprover(() -> gateService.decide(gate.getRun().getId(), gate.getId(), true, outsider, null,
                DecisionChannel.WEB));
    }

    @Test
    void anApproverNotOnTheSnapshotIsRefused() {
        var s = scenario();
        fixtures.approver(s);
        GateRequest gate = open(s, "tok-gd-6");
        UUID late = fixtures.approver(s).getId();   // joined after the gate opened

        assertNotAnApprover(() -> gateService.decide(gate.getRun().getId(), gate.getId(), true, late, null,
                DecisionChannel.WEB));
    }

    @Test
    void aBuyerOrgWithNoApproversStaysUnrestricted() {
        var s = scenario();
        GateRequest gate = open(s, "tok-gd-7");
        assertThat(gate.eligibleApproverIds()).isNull();

        gateService.decide(gate.getRun().getId(), gate.getId(), true, UUID.randomUUID(), null, DecisionChannel.WEB);

        assertThat(gates.findById(gate.getId()).orElseThrow().getStatus()).isEqualTo(GateStatus.APPROVED);
    }
}
