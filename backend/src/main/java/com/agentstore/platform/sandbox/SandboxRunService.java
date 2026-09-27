package com.agentstore.platform.sandbox;

import com.agentstore.platform.build.RebuildService;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.data.BindingResolver;
import com.agentstore.platform.data.SandboxBindingService;
import com.agentstore.platform.execution.PendingBuildRuns;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunController;
import com.agentstore.platform.execution.RunLauncher;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunStepKind;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.llm.LlmCallStatus;
import com.agentstore.platform.llm.LlmSource;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Starts, cancels and reruns sandbox runs (spec §3.1, §3.4). Every check happens under the org row lock so two
 * triggers cannot both pass the quota (spec §5).
 */
@Service
public class SandboxRunService {

    private record LlmOverride(RunLlmMode mode, UUID replayRunId, String mockJson) {
    }

    private final AgentVersionRepository versions;
    private final SandboxProfileRepository profiles;
    private final SandboxProfileService profileService;
    private final SandboxBindingService sandboxBindings;
    private final RunRepository runs;
    private final LlmCallRepository llmCalls;
    private final OrgRepository orgs;
    private final SandboxQuotaService quota;
    private final BindingResolver resolver;
    private final ManifestParser parser;
    private final RebuildService rebuild;
    private final PendingBuildRuns pendingBuildRuns;
    private final RunLauncher launcher;
    private final RunLifecycleService lifecycle;
    private final RunTokenService tokens;
    private final TransactionTemplate tx;

    public SandboxRunService(AgentVersionRepository versions, SandboxProfileRepository profiles,
                             SandboxProfileService profileService, SandboxBindingService sandboxBindings,
                             RunRepository runs, LlmCallRepository llmCalls, OrgRepository orgs,
                             SandboxQuotaService quota, BindingResolver resolver, ManifestParser parser,
                             RebuildService rebuild, PendingBuildRuns pendingBuildRuns, RunLauncher launcher,
                             RunLifecycleService lifecycle, RunTokenService tokens,
                             PlatformTransactionManager transactionManager) {
        this.versions = versions;
        this.profiles = profiles;
        this.profileService = profileService;
        this.sandboxBindings = sandboxBindings;
        this.runs = runs;
        this.llmCalls = llmCalls;
        this.orgs = orgs;
        this.quota = quota;
        this.resolver = resolver;
        this.parser = parser;
        this.rebuild = rebuild;
        this.pendingBuildRuns = pendingBuildRuns;
        this.launcher = launcher;
        this.lifecycle = lifecycle;
        this.tokens = tokens;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Run start(UUID orgId, UUID userId, UUID agentVersionId, UUID profileId, ProfileInput inline) {
        return begin(orgId, userId, ownedVersion(orgId, agentVersionId), profileId, inline, null);
    }

    public Run rerun(UUID orgId, UUID userId, UUID runId) {
        Run source = owned(orgId, runId);
        if (source.getSandboxProfileId() == null) {
            throw new SandboxApiException(HttpStatus.CONFLICT, "PROFILE_DELETED",
                    "The profile this run used no longer exists");
        }
        return begin(orgId, userId, source.getAgentVersion(), source.getSandboxProfileId(), null,
                new LlmOverride(RunLlmMode.REPLAY, runId, null));
    }

    public void cancel(UUID orgId, UUID runId) {
        owned(orgId, runId);
        if (!lifecycle.terminate(runId, RunStatus.CANCELLED, RunStepKind.CANCELLED,
                Map.of("summary", "Cancelled by the developer"))) {
            throw new SandboxApiException(HttpStatus.CONFLICT, "RUN_ALREADY_ENDED", "The run has already ended");
        }
    }

    public Run owned(UUID orgId, UUID runId) {
        Run run = runs.findById(runId).orElseThrow(() -> SandboxApiException.notFound("Run"));
        if (run.getConnection() != null || !orgId.equals(run.getInitiatedByOrgId())) {
            throw SandboxApiException.notFound("Run");
        }
        return run;
    }

    private Run begin(UUID orgId, UUID userId, AgentVersion version, UUID profileId, ProfileInput inline,
                      LlmOverride override) {
        if (version.getImageRef() == null || version.getImageRef().isBlank()) {
            throw new SandboxApiException(HttpStatus.CONFLICT, "VERSION_NOT_BUILT",
                    "This version has no built image yet; push it and wait for the build to succeed");
        }
        boolean waitForRebuild = rebuild.needsRebuild(version);
        Run run = tx.execute(status -> create(orgId, userId, version, profileId, inline, override, waitForRebuild));
        if (waitForRebuild) {
            try {
                rebuild.queueRebuild(version);
            } catch (RuntimeException e) {
                lifecycle.terminate(run.getId(), RunStatus.PENDING_BUILD, RunStatus.FAILED, RunStepKind.FAILED,
                        Map.of("summary", "Could not queue a rebuild for this version"));
                throw e;
            }
            pendingBuildRuns.settle(version.getId(), null);   // the build we joined may already have finished
            return runs.findById(run.getId()).orElseThrow();
        }
        launcher.launch(run.getId());
        return runs.findById(run.getId()).orElseThrow();
    }

    private Run create(UUID orgId, UUID userId, AgentVersion version, UUID profileId, ProfileInput inline,
                       LlmOverride override, boolean waitForRebuild) {
        orgs.findByIdForUpdate(orgId).orElseThrow(() -> SandboxApiException.notFound("Org"));
        quota.checkStart(orgId);

        SandboxProfile profile;
        if (profileId != null) {
            profile = profiles.findById(profileId).filter(p -> p.getAgentId().equals(version.getAgent().getId()))
                    .orElseThrow(() -> SandboxApiException.notFound("Profile"));
        } else {
            profile = profileService.createInline(orgId, version.getAgent().getId(), inline);
            if (inline.bindings() != null) {
                for (var entry : inline.bindings().entrySet()) {
                    sandboxBindings.bind(orgId, userId, profile.getId(), entry.getKey(), entry.getValue());
                }
            }
        }

        Manifest manifest = parser.parse(version.getManifestJson());
        var pins = resolver.resolveForProfile(profile.getId(), manifest, RunMode.SANDBOX);

        RunLlmMode llmMode = override != null ? override.mode() : profile.getLlmMode();
        UUID replayId = override != null ? override.replayRunId() : profile.getReplayRunId();
        String mockJson = override != null ? override.mockJson() : profile.getLlmMockJson();
        if (llmMode == RunLlmMode.REPLAY) {
            checkReplaySource(orgId, version.getAgent().getId(), replayId);
        }

        Instant now = Instant.now();
        BigDecimal cap = profile.getSpendCap() != null ? profile.getSpendCap() : manifest.costEnvelope().maxPerRun();
        Run pending = new Run(null, null, version, waitForRebuild ? RunStatus.PENDING_BUILD : RunStatus.PENDING, cap,
                BigDecimal.ZERO, null, RunTokenService.hash(tokens.generate()));
        pending.setMode(RunMode.SANDBOX);
        pending.setInitiatedByOrgId(orgId);
        pending.setSandboxProfileId(profile.getId());
        pending.setInputJson(profile.getInputJson());
        pending.setLlmMode(llmMode);
        pending.setReplayRunId(replayId);
        pending.setLlmMockJson(mockJson);
        pending.setStartedAt(now);
        pending.setDeadlineAt(waitForRebuild ? now.plus(RunController.PENDING_BUILD_TIMEOUT)
                : now.plusSeconds(RunController.SANDBOX_TIMEOUT_SECONDS));
        return resolver.saveWithPins(pending, pins);
    }

    private void checkReplaySource(UUID orgId, UUID agentId, UUID replayId) {
        Run source = replayId == null ? null : runs.findById(replayId).orElse(null);
        boolean validOwner = source != null && source.getConnection() == null && source.getMode() == RunMode.SANDBOX
                && orgId.equals(source.getInitiatedByOrgId()) && agentId.equals(source.getAgentVersion().getAgent().getId());
        if (!validOwner) {
            throw new SandboxApiException(HttpStatus.FORBIDDEN, "REPLAY_SOURCE_INVALID",
                    "The replay source is not a sandbox run of this agent owned by your org");
        }
        boolean hasRecording = llmCalls.existsByRunIdAndStatusAndSourceNotAndRecordingExpiresAtIsNotNullAndRecordingPurgedAtIsNull(
                replayId, LlmCallStatus.OK, LlmSource.MOCK);
        if (!hasRecording) {
            throw new SandboxApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPLAY_SOURCE_INVALID",
                    "That run has no recorded calls to replay");
        }
    }

    private AgentVersion ownedVersion(UUID orgId, UUID agentVersionId) {
        AgentVersion version = versions.findById(agentVersionId).orElseThrow(() -> SandboxApiException.notFound("Version"));
        Agent agent = version.getAgent();
        if (!agent.getDeveloperOrg().getId().equals(orgId)) {
            throw SandboxApiException.notFound("Version");
        }
        return version;
    }
}
