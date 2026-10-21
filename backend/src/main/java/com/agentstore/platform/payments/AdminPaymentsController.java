package com.agentstore.platform.payments;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/payments/reconciliation")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentsController {

    public record ResolveRequest(String note) {}

    private final ReconciliationService reconciliation;

    public AdminPaymentsController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @GetMapping
    public List<ReconciliationIssueView> issues(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reconciliation.issuesFor(date);
    }

    @PostMapping("/run")
    public Map<String, Integer> run(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Map.of("issuesFound", reconciliation.reconcile(date));
    }

    @PostMapping("/{issueId}/resolve")
    public Map<String, Object> resolve(@PathVariable UUID issueId, @RequestBody ResolveRequest request) {
        if (!reconciliation.resolve(issueId, request.note())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue not found");
        }
        return Map.of("id", issueId, "resolved", true);
    }
}
