package com.agentstore.platform.sandbox;

import com.agentstore.platform.data.Caller;
import com.agentstore.platform.execution.GateService;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sandbox/runs")
@PreAuthorize("hasRole('DEVELOPER')")
public class SandboxRunController {

    private static final int LIST_LIMIT = 100;

    public record StartRequest(@NotNull UUID agentVersionId, UUID profileId, ProfileInput inlineProfile) {
    }

    public record GateDecision(boolean approve, @Size(max = 1000) String note) {
    }

    private final SandboxRunService service;
    private final SandboxRunViews views;
    private final RunRepository runs;
    private final GateService gateService;
    private final com.agentstore.platform.execution.RunLookup runLookup;

    public SandboxRunController(SandboxRunService service, SandboxRunViews views, RunRepository runs, GateService gateService,
                                com.agentstore.platform.execution.RunLookup runLookup) {
        this.service = service;
        this.views = views;
        this.runs = runs;
        this.gateService = gateService;
        this.runLookup = runLookup;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SandboxRunViews.SandboxRunSummary create(@jakarta.validation.Valid @RequestBody StartRequest request) {
        boolean hasProfileId = request.profileId() != null;
        boolean hasInline = request.inlineProfile() != null;
        if (hasProfileId == hasInline) {
            throw SandboxApiException.invalid("Exactly one of profileId or inlineProfile is required");
        }
        var claims = Caller.current();
        Run run = service.start(claims.orgId(), claims.userId(), request.agentVersionId(), request.profileId(),
                request.inlineProfile());
        return views.summary(run);
    }

    @GetMapping
    public List<SandboxRunViews.SandboxRunSummary> list(@RequestParam(required = false) UUID agentId) {
        var claims = Caller.current();
        var page = PageRequest.of(0, LIST_LIMIT);
        var found = agentId == null
                ? runs.findByModeAndInitiatedByOrgIdOrderByStartedAtDesc(RunMode.SANDBOX, claims.orgId(), page)
                : runs.findByModeAndInitiatedByOrgIdAndAgentVersionAgentIdOrderByStartedAtDesc(RunMode.SANDBOX,
                        claims.orgId(), agentId, page);
        return found.map(views::summary).toList();
    }

    @GetMapping("/{id}")
    public SandboxRunViews.SandboxRunDetail get(@PathVariable("id") String runRef) {
        return views.detail(service.owned(Caller.current().orgId(), runLookup.idOf(runRef)));
    }

    @PostMapping("/{id}/cancel")
    public SandboxRunViews.SandboxRunSummary cancel(@PathVariable("id") String runRef) {
        UUID id = runLookup.idOf(runRef);
        var claims = Caller.current();
        service.cancel(claims.orgId(), id);
        return views.summary(service.owned(claims.orgId(), id));
    }

    @PostMapping("/{id}/rerun")
    @ResponseStatus(HttpStatus.CREATED)
    public SandboxRunViews.SandboxRunSummary rerun(@PathVariable("id") String runRef) {
        UUID id = runLookup.idOf(runRef);
        var claims = Caller.current();
        return views.summary(service.rerun(claims.orgId(), claims.userId(), id));
    }

    @PostMapping("/{id}/gates/{gateId}/decision")
    public SandboxRunViews.SandboxRunSummary decide(@PathVariable("id") String runRef, @PathVariable UUID gateId,
                                                    @jakarta.validation.Valid @RequestBody GateDecision body) {
        UUID id = runLookup.idOf(runRef);
        var claims = Caller.current();
        Run run = service.owned(claims.orgId(), id);
        gateService.decide(run.getId(), gateId, body.approve(), claims.userId(), body.note());
        return views.summary(service.owned(claims.orgId(), id));
    }
}
