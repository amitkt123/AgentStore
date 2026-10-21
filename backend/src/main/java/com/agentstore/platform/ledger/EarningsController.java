package com.agentstore.platform.ledger;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.OrgRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** A developer org's earnings and statement, read straight from its DEVELOPER_PAYABLE postings. Creates nothing. */
@RestController
public class EarningsController {

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerAccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final OrgRepository orgRepository;
    private final MoneyProperties moneyProperties;

    public EarningsController(LedgerTransactionRepository transactionRepository,
                              LedgerAccountRepository accountRepository, LedgerService ledgerService,
                              OrgRepository orgRepository, MoneyProperties moneyProperties) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.orgRepository = orgRepository;
        this.moneyProperties = moneyProperties;
    }

    @GetMapping("/api/v1/developer/earnings")
    @PreAuthorize("hasAnyRole('DEVELOPER','ADMIN')")
    public EarningsResponse earnings(@RequestParam(required = false) String month) {
        UUID orgId = callerOrg();
        YearMonth ym = parse(month);
        Optional<UUID> account = payableAccount(orgId);
        var rows = account.map(a -> rowsFor(a, ym)).orElse(List.of());

        BigDecimal buyersPaid = BigDecimal.ZERO, fee = BigDecimal.ZERO, ai = BigDecimal.ZERO, tax = BigDecimal.ZERO,
                earned = BigDecimal.ZERO;
        Map<UUID, Totals> byAgent = new LinkedHashMap<>();
        for (var row : rows) {
            buyersPaid = buyersPaid.add(row.getBuyerPaid());
            fee = fee.add(row.getPlatformFee());
            ai = ai.add(row.getAiUsage());
            tax = tax.add(row.getTax());
            earned = earned.add(row.getEarned());
            if (row.getAgentId() != null) {
                byAgent.computeIfAbsent(row.getAgentId(), id -> new Totals(row.getAgentName())).add(row);
            }
        }
        List<EarningsResponse.ByAgent> agents = byAgent.entrySet().stream()
                .map(e -> e.getValue().toResponse(e.getKey()))
                .sorted((x, y) -> y.earned().compareTo(x.earned()))
                .toList();
        BigDecimal available = account.map(ledgerService::balanceOf).orElse(BigDecimal.ZERO);
        return new EarningsResponse(ym.toString(), buyersPaid, fee, feePercent(orgId), ai, tax, earned, available, agents);
    }

    @GetMapping("/api/v1/developer/statements/{month}/download")
    @PreAuthorize("hasAnyRole('DEVELOPER','ADMIN')")
    public ResponseEntity<byte[]> download(@PathVariable String month) {
        YearMonth ym = parse(month);
        var rows = payableAccount(callerOrg()).map(a -> rowsFor(a, ym)).orElse(List.of());
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("karya-statement-" + ym + ".csv").build().toString())
                .body(EarningsStatementCsv.render(rows).getBytes(StandardCharsets.UTF_8));
    }

    private List<LedgerTransactionRepository.DeveloperTransactionRow> rowsFor(UUID account, YearMonth ym) {
        Instant from = ym.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return transactionRepository.developerTransactionsBetween(account, from, to);
    }

    private Optional<UUID> payableAccount(UUID orgId) {
        return accountRepository.findByOrgIdAndKind(orgId, LedgerAccountKind.DEVELOPER_PAYABLE).map(LedgerAccount::getId);
    }

    /** The org's override, else the configured default (an agent's own override is not reflected here). */
    private BigDecimal feePercent(UUID orgId) {
        return orgRepository.findById(orgId).map(o -> o.getPlatformFeePercent())
                .orElse(moneyProperties.platformFeePercent());
    }

    private static UUID callerOrg() {
        return ((JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).orgId();
    }

    private static YearMonth parse(String month) {
        try {
            return month == null ? YearMonth.now(ZoneOffset.UTC) : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
        }
    }

    private static final class Totals {
        private final String name;
        private final Set<UUID> runIds = new HashSet<>();
        private BigDecimal buyersPaid = BigDecimal.ZERO, fee = BigDecimal.ZERO, ai = BigDecimal.ZERO,
                earned = BigDecimal.ZERO;

        Totals(String name) {
            this.name = name;
        }

        void add(LedgerTransactionRepository.DeveloperTransactionRow row) {
            if ("RUN_CHARGE".equals(row.getType()) && row.getRunId() != null) runIds.add(row.getRunId());
            buyersPaid = buyersPaid.add(row.getBuyerPaid());
            fee = fee.add(row.getPlatformFee());
            ai = ai.add(row.getAiUsage());
            earned = earned.add(row.getEarned());
        }

        EarningsResponse.ByAgent toResponse(UUID agentId) {
            int runs = runIds.size();
            BigDecimal perRun = runs == 0 ? BigDecimal.ZERO.setScale(2)
                    : earned.divide(BigDecimal.valueOf(runs), 2, RoundingMode.HALF_UP);
            return new EarningsResponse.ByAgent(agentId, name, runs, buyersPaid, fee, ai, earned, perRun);
        }
    }
}
