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

    public RunDetailController(RunRepository runRepository, RunStepRepository runStepRepository) {
        this.runRepository = runRepository;
        this.runStepRepository = runStepRepository;
    }

    @GetMapping("/{runId}")
    @PreAuthorize("isAuthenticated()")
    public RunDetailResponse get(@PathVariable UUID runId) {
        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));

        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        boolean owns = run.getConnection().getBuyerOrg().getId().equals(claims.orgId());
        if (!owns && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Run not owned by caller's org");
        }

        var steps = runStepRepository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(RunStepResponse::from).toList();
        return new RunDetailResponse(run.getId(), run.getStatus(), run.getCostCap(), run.getTotalCharged(), steps);
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
}
