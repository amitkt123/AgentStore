package com.agentstore.platform.sandbox;

import com.agentstore.platform.build.TemplateBuildRepository;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.sandbox.SandboxQuotaProperties.Limits;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * The five sandbox quotas (spec §5). Usage is always computed from {@code run} and {@code llm_call}, never stored: no
 * drift, no reset job.
 */
@Service
public class SandboxQuotaService {

    static final List<RunStatus> OPEN =
            List.of(RunStatus.PENDING, RunStatus.PENDING_BUILD, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

    private final OrgRepository orgs;
    private final RunRepository runs;
    private final DatasetRepository datasets;
    private final TemplateBuildRepository builds;
    private final LlmCallRepository llmCalls;
    private final SandboxQuotaProperties props;

    public SandboxQuotaService(OrgRepository orgs, RunRepository runs, DatasetRepository datasets,
                               TemplateBuildRepository builds, LlmCallRepository llmCalls, SandboxQuotaProperties props) {
        this.orgs = orgs;
        this.runs = runs;
        this.datasets = datasets;
        this.builds = builds;
        this.llmCalls = llmCalls;
        this.props = props;
    }

    static Instant monthStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    static Instant dayStart() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Caller holds the org row lock (SandboxRunService), so two triggers cannot both pass. */
    public void checkStart(UUID orgId) {
        Limits limits = limits(orgId);
        long open = runs.countByModeAndInitiatedByOrgIdAndStatusIn(RunMode.SANDBOX, orgId, OPEN);
        if (open >= limits.concurrentRuns()) {
            throw SandboxApiException.quota("concurrentRuns", open, limits.concurrentRuns());
        }
        BigDecimal seconds = runs.sandboxRunSecondsSince(orgId, monthStart());
        if (seconds.compareTo(BigDecimal.valueOf(limits.runSeconds())) >= 0) {
            throw SandboxApiException.quota("runSeconds", seconds.setScale(0, RoundingMode.DOWN), limits.runSeconds());
        }
    }

    public void checkFixtureStorage(UUID orgId, long incomingBytes) {
        Limits limits = limits(orgId);
        long used = datasets.usedBytesByPurpose(orgId, DatasetPurpose.FIXTURE);
        if (used + incomingBytes > limits.fixtureBytes()) {
            throw SandboxApiException.quota("fixtureBytes", used, limits.fixtureBytes());
        }
    }

    public void checkBuild(UUID orgId) {
        Limits limits = limits(orgId);
        long used = builds.countQueuedSince(orgId, dayStart());
        if (used >= limits.buildsPerDay()) {
            throw SandboxApiException.quota("buildsToday", used, limits.buildsPerDay());
        }
    }

    public boolean llmAllows(UUID orgId, BigDecimal estimatedMaxCost) {
        BigDecimal spent = llmCalls.sandboxSpendSince(orgId, monthStart());
        return spent.add(estimatedMaxCost).compareTo(limits(orgId).llmSpend()) <= 0;
    }

    public QuotaView view(UUID orgId) {
        Limits unverified = props.unverified();
        Limits verifiedLimits = props.verified();

        long openRuns = runs.countByModeAndInitiatedByOrgIdAndStatusIn(RunMode.SANDBOX, orgId, OPEN);
        BigDecimal seconds = runs.sandboxRunSecondsSince(orgId, monthStart());
        BigDecimal llmSpend = llmCalls.sandboxSpendSince(orgId, monthStart());
        long fixtureBytes = datasets.usedBytesByPurpose(orgId, DatasetPurpose.FIXTURE);
        long buildsToday = builds.countQueuedSince(orgId, dayStart());

        return new QuotaView(
                new Entry(seconds.setScale(0, RoundingMode.DOWN), BigDecimal.valueOf(unverified.runSeconds()), BigDecimal.valueOf(verifiedLimits.runSeconds())),
                new Entry(BigDecimal.valueOf(openRuns), BigDecimal.valueOf(unverified.concurrentRuns()), BigDecimal.valueOf(verifiedLimits.concurrentRuns())),
                new Entry(llmSpend, unverified.llmSpend(), verifiedLimits.llmSpend()),
                new Entry(BigDecimal.valueOf(fixtureBytes), BigDecimal.valueOf(unverified.fixtureBytes()), BigDecimal.valueOf(verifiedLimits.fixtureBytes())),
                new Entry(BigDecimal.valueOf(buildsToday), BigDecimal.valueOf(unverified.buildsPerDay()), BigDecimal.valueOf(verifiedLimits.buildsPerDay())));
    }

    private Limits limits(UUID orgId) {
        return props.forOrg(orgs.findById(orgId).orElseThrow());
    }

    public record Entry(BigDecimal used, BigDecimal limit, BigDecimal verifiedLimit) {
    }

    public record QuotaView(Entry runSeconds, Entry concurrentRuns, Entry llmSpend, Entry fixtureBytes, Entry buildsToday) {
    }
}
