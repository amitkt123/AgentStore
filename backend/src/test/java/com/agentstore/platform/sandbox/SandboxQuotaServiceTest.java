package com.agentstore.platform.sandbox;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.OrgRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxQuotaServiceTest extends AbstractIntegrationTest {

    @Autowired SandboxFixtures fx;
    @Autowired SandboxQuotaService quota;
    @Autowired SandboxQuotaProperties props;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrgRepository orgs;
    @Autowired RunLifecycleService lifecycle;
    @Autowired RunRepository runs;

    void setRunSeconds(Run run, Instant startedAt, Instant completedAt) {
        jdbc.update("UPDATE run SET started_at = ?, completed_at = ? WHERE id = ?",
                startedAt == null ? null : java.sql.Timestamp.from(startedAt),
                completedAt == null ? null : java.sql.Timestamp.from(completedAt), run.getId());
    }

    @Test
    void unverifiedOrgGetsTheUnverifiedLimitsAndVerifiedGetsTheOthers() {
        var dev = fx.developer("valid-tier-a.json");

        var unverified = props.forOrg(reload(dev));
        assertThat(unverified).isEqualTo(props.unverified());

        fx.verify(dev.org());
        var verified = props.forOrg(reload(dev));
        assertThat(verified).isEqualTo(props.verified());
        assertThat(verified).isNotEqualTo(unverified);
    }

    com.agentstore.platform.identity.Org reload(SandboxFixtures.Dev dev) {
        return orgs.findById(dev.org().getId()).orElseThrow();
    }

    @Test
    void concurrencyCountsNonTerminalSandboxRunsIncludingPendingBuild() {
        var dev = fx.developer("valid-tier-a.json");
        fx.sandboxRun(dev, fx.profile(dev, "a"), RunStatus.PENDING_BUILD);

        assertThatThrownBy(() -> quota.checkStart(dev.org().getId()))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).extra()).containsEntry("limit", "concurrentRuns"));

        fx.verify(dev.org());
        for (int i = 0; i < 3; i++) {
            fx.sandboxRun(dev, fx.profile(dev, "v" + i), RunStatus.RUNNING);
        }
        quota.checkStart(dev.org().getId());   // 4 open, verified limit 5: passes
        fx.sandboxRun(dev, fx.profile(dev, "v4"), RunStatus.AWAITING_APPROVAL);
        assertThatThrownBy(() -> quota.checkStart(dev.org().getId())).isInstanceOf(SandboxApiException.class);
    }

    @Test
    void aCompletedRunDoesNotCountTowardConcurrency() {
        var dev = fx.developer("valid-tier-a.json");
        fx.sandboxRun(dev, fx.profile(dev, "done"), RunStatus.COMPLETED);

        quota.checkStart(dev.org().getId());   // does not throw
    }

    @Test
    void runSecondsSumThisMonthOnlyAndExcludePendingBuild() {
        var dev = fx.developer("valid-tier-a.json");
        Instant monthStart = Instant.now().truncatedTo(ChronoUnit.DAYS)
                .atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Run thisMonth = fx.sandboxRun(dev, fx.profile(dev, "a"), RunStatus.COMPLETED);
        setRunSeconds(thisMonth, monthStart.plusSeconds(10), monthStart.plusSeconds(10 + props.unverified().runSeconds()));

        assertThatThrownBy(() -> quota.checkStart(dev.org().getId()))
                .isInstanceOf(SandboxApiException.class)
                .satisfies(e -> assertThat(((SandboxApiException) e).extra()).containsEntry("limit", "runSeconds"));
    }

    @Test
    void aRunFromThePreviousMonthDoesNotCount() {
        var dev = fx.developer("valid-tier-a.json");
        Instant monthStart = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant lastMonth = monthStart.minusSeconds(3600);

        Run old = fx.sandboxRun(dev, fx.profile(dev, "old"), RunStatus.COMPLETED);
        setRunSeconds(old, lastMonth.minusSeconds(props.unverified().runSeconds() + 100), lastMonth);

        quota.checkStart(dev.org().getId());   // does not throw
    }

    @Test
    void aPendingBuildRunsSecondsDoNotCountTowardTheRunSecondsLimit() {
        var dev = fx.developer("valid-tier-a.json");
        fx.verify(dev.org());   // concurrency limit 5, so the one PENDING_BUILD run below does not also trip that limit
        Instant monthStart = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Run pendingBuild = fx.sandboxRun(dev, fx.profile(dev, "pb"), RunStatus.PENDING_BUILD);
        setRunSeconds(pendingBuild, monthStart.plusSeconds(1), monthStart.plusSeconds(1 + props.verified().runSeconds() * 2));

        quota.checkStart(dev.org().getId());   // does not throw: PENDING_BUILD seconds are excluded from the sum
    }

    @Test
    void aRunsBuildWaitDoesNotCountOnceItIsPromotedAndLaterFinishes() {
        var dev = fx.developer("valid-tier-a.json");
        Run pendingBuild = fx.sandboxRun(dev, fx.profile(dev, "waited"), RunStatus.PENDING_BUILD);
        Instant longAgo = Instant.now().minusSeconds(props.unverified().runSeconds() * 2);
        setRunSeconds(pendingBuild, longAgo, null);   // the run waited for a rebuild for a very long time

        lifecycle.promotePendingBuild(pendingBuild.getId(), Instant.now().plusSeconds(600));

        Run promoted = runs.findById(pendingBuild.getId()).orElseThrow();
        assertThat(promoted.getStartedAt()).as("the wait must not be backdated into the run's billed time")
                .isAfter(Instant.now().minusSeconds(30));
        // finish the run now, as if it ran briefly after promotion, and confirm the old wait was never counted
        jdbc.update("UPDATE run SET status = 'COMPLETED', completed_at = now() WHERE id = ?", pendingBuild.getId());
        quota.checkStart(dev.org().getId());   // does not throw: run-seconds stayed near zero, not ~2x the monthly limit
    }

    @Test
    void anotherOrgsAndProductionRunsAreIgnored() {
        var dev = fx.developer("valid-tier-a.json");
        var other = fx.developer("valid-tier-a.json");
        fx.sandboxRun(other, fx.profile(other, "a"), RunStatus.RUNNING);

        quota.checkStart(dev.org().getId());   // does not throw: the open run belongs to another org
    }
}
