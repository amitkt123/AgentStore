package com.agentstore.platform.payments;

import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerChecks;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.LedgerTransactionType;
import com.agentstore.platform.ledger.Posting;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compares one UTC day of the provider's payments, refunds and settlements with our records, flags differences as
 * {@code reconciliation_issue} rows (once per day, kind and provider ref), and posts each settlement's gateway fee.
 * Provider calls are made outside any database transaction.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    /** A provider refund younger than this may not be stored locally yet, so it is not flagged as unmatched. */
    static final java.time.Duration UNMATCHED_REFUND_GRACE = java.time.Duration.ofHours(1);

    private final PaymentProvider provider;
    private final ReconciliationIssues issues;
    private final LedgerChecks ledgerChecks;
    private final LedgerService ledgerService;
    private final LedgerAccounts ledgerAccounts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public ReconciliationService(PaymentProvider provider, ReconciliationIssues issues, LedgerChecks ledgerChecks,
                                 LedgerService ledgerService, LedgerAccounts ledgerAccounts, JdbcTemplate jdbc,
                                 ObjectMapper mapper, PlatformTransactionManager transactionManager,
                                 MeterRegistry meters) {
        this.provider = provider;
        this.issues = issues;
        this.ledgerChecks = ledgerChecks;
        this.ledgerService = ledgerService;
        this.ledgerAccounts = ledgerAccounts;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
        Gauge.builder("agentstore.money.reconciliation_issues", this, ReconciliationService::unresolvedCount)
                .register(meters);
    }

    /** Reconciles {@code dayUtc}; returns how many issues it found (new or already recorded). Safe to repeat. */
    public int reconcile(LocalDate dayUtc) {
        return reconcile(dayUtc, Instant.now());
    }

    /** {@link #reconcile(LocalDate)} as of {@code now}, which decides whether a provider refund is still too new to judge. */
    int reconcile(LocalDate dayUtc, Instant now) {
        // Provider calls first, outside any transaction.
        List<PaymentProvider.ProviderPayment> payments = provider.fetchPayments(dayUtc);
        List<PaymentProvider.ProviderRefund> refunds = provider.fetchRefunds(dayUtc);
        List<PaymentProvider.ProviderSettlement> settlements = provider.fetchSettlements(dayUtc);

        int found = 0;
        found += checkPayments(dayUtc, payments);
        found += checkPaidOrders(dayUtc, payments);
        found += checkRefunds(dayUtc, refunds, now);
        postGatewayFees(settlements);
        found += checkLedger(dayUtc);
        return found;
    }

    private int checkPayments(LocalDate day, List<PaymentProvider.ProviderPayment> payments) {
        int found = 0;
        for (PaymentProvider.ProviderPayment p : payments) {
            if (!"captured".equals(p.status())) continue;
            List<Map<String, Object>> local = jdbc.queryForList(
                    "select amount from payment_order where provider_payment_id = ? and status = 'PAID'", p.paymentId());
            if (local.isEmpty()) {
                if (isKnown(p)) {
                    found += flag(day, "MISSING_LOCALLY", p.paymentId(), detail("orderId", p.orderId(),
                            "virtualAccountId", p.virtualAccountId(), "amount", p.amount()));
                }
            } else {
                BigDecimal ours = (BigDecimal) local.get(0).get("amount");
                if (p.amount() == null || ours.compareTo(p.amount()) != 0) {
                    found += flag(day, "AMOUNT_MISMATCH", p.paymentId(),
                            detail("providerAmount", p.amount(), "localAmount", ours));
                }
            }
        }
        return found;
    }

    private boolean isKnown(PaymentProvider.ProviderPayment p) {
        if (p.orderId() != null && count("select count(*) from payment_order where provider_order_id = ?", p.orderId()) > 0) {
            return true;
        }
        return p.virtualAccountId() != null
                && count("select count(*) from virtual_account where provider_va_id = ?", p.virtualAccountId()) > 0;
    }

    private int checkPaidOrders(LocalDate day, List<PaymentProvider.ProviderPayment> payments) {
        Set<String> providerIds = new HashSet<>();
        payments.forEach(p -> providerIds.add(p.paymentId()));
        Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Map<String, Object>> paid = jdbc.queryForList("select provider_payment_id, amount from payment_order "
                + "where status = 'PAID' and provider_payment_id is not null and paid_at >= ? and paid_at < ?",
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(from.plusSeconds(86400)));
        int found = 0;
        for (Map<String, Object> row : paid) {
            String paymentId = (String) row.get("provider_payment_id");
            if (!providerIds.contains(paymentId)) {
                found += flag(day, "MISSING_AT_PROVIDER", paymentId, detail("amount", row.get("amount")));
            }
        }
        return found;
    }

    private int checkRefunds(LocalDate day, List<PaymentProvider.ProviderRefund> refunds, Instant now) {
        int found = 0;
        for (PaymentProvider.ProviderRefund r : refunds) {
            List<String> parts = jdbc.queryForList(
                    "select status from buyer_refund_part where provider_refund_id = ?", String.class, r.refundId());
            if (parts.isEmpty()) {
                String otherRefundId = refundIdOfReceiptPart(r);
                if (otherRefundId != null) {
                    // The part named by the receipt already holds another provider refund: it was paid out twice.
                    found += flag(day, "DUPLICATE_REFUND", r.refundId(), detail("paymentId", r.paymentId(),
                            "amount", r.amount(), "refundPartId", r.receipt(), "partRefundId", otherRefundId));
                } else if (!awaitingLocalPart(r, now)) {
                    found += flag(day, "REFUND_UNMATCHED", r.refundId(), detail("paymentId", r.paymentId(),
                            "amount", r.amount(), "providerStatus", r.status(), "receipt", r.receipt()));
                }
                continue;
            }
            String local = parts.get(0);
            if (!refundStatusAgrees(r.status(), local)) {
                found += flag(day, "REFUND_MISMATCH", r.refundId(),
                        detail("providerStatus", r.status(), "localStatus", local, "paymentId", r.paymentId()));
            }
        }
        return found;
    }

    /**
     * A provider refund with no part holding its id is ours when its receipt is a part id that has no provider refund
     * id yet (the refund call timed out before we stored it; the retry job settles it) and possibly ours when it is
     * under an hour old at {@code now} (the submitting transaction may not have committed yet). Anything else was
     * made outside Karya.
     */
    private boolean awaitingLocalPart(PaymentProvider.ProviderRefund r, Instant now) {
        if (r.createdAt() != null && r.createdAt().isAfter(now.minus(UNMATCHED_REFUND_GRACE))) return true;
        UUID partId = receiptPartId(r);
        return partId != null
                && count("select count(*) from buyer_refund_part where id = ? and provider_refund_id is null", partId) > 0;
    }

    /** The provider refund id already stored on the part named by {@code r}'s receipt, if that part has one. */
    private String refundIdOfReceiptPart(PaymentProvider.ProviderRefund r) {
        UUID partId = receiptPartId(r);
        if (partId == null) return null;
        List<String> ids = jdbc.queryForList("select provider_refund_id from buyer_refund_part "
                + "where id = ? and provider_refund_id is not null", String.class, partId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private static UUID receiptPartId(PaymentProvider.ProviderRefund r) {
        if (r.receipt() == null) return null;
        try {
            return UUID.fromString(r.receipt());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean refundStatusAgrees(String provider, String local) {
        if (provider == null) return false;
        return switch (provider) {
            case "PROCESSED" -> "DONE".equals(local);
            case "FAILED" -> "FAILED".equals(local);
            case "PENDING" -> "PENDING".equals(local) || "PROCESSING".equals(local);
            default -> false;
        };
    }

    /** One ADJUSTMENT per settlement, keyed {@code gateway-fee:{settlementId}}, so repeats post nothing. */
    private void postGatewayFees(List<PaymentProvider.ProviderSettlement> settlements) {
        for (PaymentProvider.ProviderSettlement s : settlements) {
            BigDecimal fee = s.fees();
            if (fee == null || fee.signum() <= 0) continue;
            // Accounts are created outside the transaction, as AdminMoneyController does.
            UUID clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYMENT_CLEARING);
            UUID gatewayFees = ledgerAccounts.platformAccount(LedgerAccountKind.GATEWAY_FEES);
            Map<String, Object> reference = new HashMap<>();
            reference.put("settlementId", s.settlementId());
            reference.put("fees", fee.toPlainString());
            tx.executeWithoutResult(status -> ledgerService.post(LedgerTransactionType.ADJUSTMENT,
                    "gateway-fee:" + s.settlementId(), null, null, reference,
                    List.of(new Posting(clearing, fee), new Posting(gatewayFees, fee.negate()))));
        }
    }

    private int checkLedger(LocalDate day) {
        LedgerChecks.Result r = ledgerChecks.run();
        int found = 0;
        if (!r.totalsZero()) found += flag(day, "LEDGER_CHECK_FAILED", "totalsZero", detail());
        if (!r.balancesMatchPostings()) found += flag(day, "LEDGER_CHECK_FAILED", "balancesMatchPostings", detail());
        if (!r.walletsMatchLedger()) found += flag(day, "LEDGER_CHECK_FAILED", "walletsMatchLedger", detail());
        return found;
    }

    private int flag(LocalDate day, String kind, String providerRef, Map<String, Object> detail) {
        log.error("Reconciliation issue {} for {} on {}: {}", kind, providerRef, day, detail);
        issues.record(day, kind, providerRef, detail);
        return 1;
    }

    /** Marks the issue resolved with an operator note; returns false when no such issue exists. */
    public boolean resolve(UUID issueId, String note) {
        String json;
        try {
            json = mapper.writeValueAsString(Map.of("resolutionNote", note == null ? "" : note));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        return jdbc.update("update reconciliation_issue set resolved_at = coalesce(resolved_at, now()), "
                + "detail = coalesce(detail, '{}'::jsonb) || ?::jsonb where id = ?", json, issueId) > 0;
    }

    public List<ReconciliationIssueView> issuesFor(LocalDate day) {
        return jdbc.query("select id, day, kind, provider_ref, detail::text as detail, created_at, resolved_at "
                + "from reconciliation_issue where day = ? order by created_at, kind, provider_ref", (rs, i) -> {
            Object detail = null;
            String raw = rs.getString("detail");
            if (raw != null) {
                try {
                    detail = mapper.readValue(raw, Object.class);
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    detail = raw;
                }
            }
            java.sql.Timestamp resolved = rs.getTimestamp("resolved_at");
            return new ReconciliationIssueView(rs.getObject("id", UUID.class), rs.getDate("day").toLocalDate(),
                    rs.getString("kind"), rs.getString("provider_ref"), detail,
                    rs.getTimestamp("created_at").toInstant(), resolved == null ? null : resolved.toInstant());
        }, Date.valueOf(day));
    }

    public double unresolvedCount() {
        try {
            return count("select count(*) from reconciliation_issue where resolved_at is null");
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1] instanceof BigDecimal b ? b.toPlainString() : kv[i + 1]);
        }
        return m;
    }
}
