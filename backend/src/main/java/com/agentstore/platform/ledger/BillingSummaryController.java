package com.agentstore.platform.ledger;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class BillingSummaryController {

    private final LedgerTransactionRepository transactionRepository;
    private final ManifestParser manifestParser;

    public BillingSummaryController(LedgerTransactionRepository transactionRepository,
                                    ManifestParser manifestParser) {
        this.transactionRepository = transactionRepository;
        this.manifestParser = manifestParser;
    }

    @GetMapping("/api/v1/orgs/{orgId}/billing/summary")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','ADMIN')")
    public BillingSummaryResponse summary(@PathVariable UUID orgId,
                                          @RequestParam(required = false) String month) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!orgId.equals(claims.orgId()) && claims.role() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot read another org's billing");
        }
        YearMonth ym;
        try {
            ym = month == null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
        Instant from = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<String, String> unitLabels = new HashMap<>();   // by manifest text: many runs share a version
        Map<UUID, List<BillingSummaryResponse.Line>> linesByAgent = new LinkedHashMap<>();
        Map<UUID, String> agentNames = new HashMap<>();
        for (var row : transactionRepository.runChargesBetween(orgId, from, to)) {   // newest first
            String unit = unitLabels.computeIfAbsent(row.getManifestJson(),
                    json -> manifestParser.parse(json).costEnvelope().unitLabel());
            agentNames.put(row.getAgentId(), row.getAgentName());
            linesByAgent.computeIfAbsent(row.getAgentId(), id -> new ArrayList<>())
                    .add(new BillingSummaryResponse.Line(row.getRunDisplayId(), row.getUnits(), unit, row.getAmount(),
                            row.getTerminalReason(), row.getDecidedBy(), row.getAt()));
        }
        List<BillingSummaryResponse.AgentLines> agents = linesByAgent.entrySet().stream()
                .map(e -> new BillingSummaryResponse.AgentLines(e.getKey(), agentNames.get(e.getKey()),
                        e.getValue().size(), sum(e.getValue()), e.getValue()))
                .sorted(Comparator.comparing(BillingSummaryResponse.AgentLines::total).reversed())
                .toList();
        BigDecimal total = agents.stream().map(BillingSummaryResponse.AgentLines::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BillingSummaryResponse(ym.toString(), total, agents);
    }

    private static BigDecimal sum(List<BillingSummaryResponse.Line> lines) {
        return lines.stream().map(BillingSummaryResponse.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
