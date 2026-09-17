package com.agentstore.platform.ledger;

import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
public class StatementController {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final RunRepository runRepository;

    public StatementController(LedgerEntryRepository ledgerEntryRepository, RunRepository runRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.runRepository = runRepository;
    }

    @GetMapping("/api/v1/orgs/{orgId}/statement")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public List<StatementLineResponse> statement(@PathVariable UUID orgId) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!orgId.equals(claims.orgId()) && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot read another org's statement");
        }

        return ledgerEntryRepository.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(entry -> {
                    String agentName = runRepository.findById(entry.getRunId())
                            .map(run -> run.getConnection().getAgent().getName())
                            .orElse("unknown");
                    return new StatementLineResponse(entry.getRunId(), agentName, entry.getAmount(),
                            entry.getType(), entry.getCreatedAt());
                })
                .toList();
    }
}
