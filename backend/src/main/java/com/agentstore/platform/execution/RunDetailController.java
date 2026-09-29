package com.agentstore.platform.execution;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
public class RunDetailController {

    private final RunRepository runRepository;
    private final RunStepRepository runStepRepository;
    private final RunFlowService flowService;

    public RunDetailController(RunRepository runRepository, RunStepRepository runStepRepository, RunFlowService flowService) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
        this.flowService = flowService;
    }

    @GetMapping("/{runId}")
    @PreAuthorize("isAuthenticated()")
    public RunDetailResponse get(@PathVariable UUID runId) {
        Run run = findRun(runId);
        if (run.getConnection() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found");   // a sandbox run is not a buyer's
        }
        requireViewable(run);

        var steps = runStepRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(RunStepResponse::from).toList();
        return new RunDetailResponse(run.getId(), run.getDisplayId(), run.getStatus(), run.getTerminalReason(), run.getCostCap(), run.getTotalCharged(), steps);
    }

    /** Any run the caller can already view — unlike {@link #get}, not restricted to a buyer's own run (spec §4). */
    @GetMapping("/{runId}/flow")
    @PreAuthorize("isAuthenticated()")
    public FlowView flow(@PathVariable UUID runId) {
        Run run = findRun(runId);
        requireViewable(run);
        return flowService.compute(run);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public java.util.List<RunSummaryResponse> mine(@RequestParam(required = false) RunStatus status) {
        var claims = (JwtService.Claims)
                SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var runs = status == null
                ? runRepository.findByConnectionBuyerOrgIdOrderByStartedAtDesc(claims.orgId())
                : runRepository.findByConnectionBuyerOrgIdAndStatusOrderByStartedAtDesc(claims.orgId(), status);
        return runs.stream().map(RunSummaryResponse::from).toList();
    }

    private Run findRun(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
    }

    /** Same ownership rule as the run-detail endpoint, generalized to a run with no buyer (a sandbox run's initiator). */
    private void requireViewable(Run run) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean owns = run.getConnection() != null
                ? run.getConnection().getBuyerOrg().getId().equals(claims.orgId())
                : claims.orgId().equals(run.getInitiatedByOrgId());
        if (!owns && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }
    }
}
