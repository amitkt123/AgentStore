package com.agentstore.platform.payouts;

import com.agentstore.platform.payments.ReconciliationIssues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The payout half of the nightly reconciliation: compares one UTC day of the provider's payouts with our
 * withdrawals and records differences as {@code reconciliation_issue} rows (once per day, kind and provider ref).
 * The provider call is made before any database work, outside any transaction.
 */
@Component
public class PayoutReconciliation {

    private static final Logger log = LoggerFactory.getLogger(PayoutReconciliation.class);
    /** A provider payout younger than this may not be committed locally yet, so it is not flagged as unmatched. */
    static final Duration UNMATCHED_GRACE = Duration.ofHours(1);

    private final PayoutProvider provider;
    private final ReconciliationIssues issues;
    private final JdbcTemplate jdbc;

    public PayoutReconciliation(PayoutProvider provider, ReconciliationIssues issues, JdbcTemplate jdbc) {
        this.provider = provider;
        this.issues = issues;
        this.jdbc = jdbc;
    }

    /** Checks {@code day} as of {@code now}; returns how many issues it found (new or already recorded). */
    public int check(LocalDate day, Instant now) {
        List<PayoutProvider.ProviderPayout> payouts = provider.fetchPayouts(day);

        int found = 0;
        Set<String> providerIds = new HashSet<>();
        for (PayoutProvider.ProviderPayout p : payouts) {
            providerIds.add(p.payoutId());
            found += checkPayout(day, p, now);
        }
        found += checkProcessedWithdrawals(day, providerIds);
        return found;
    }

    private int checkPayout(LocalDate day, PayoutProvider.ProviderPayout p, Instant now) {
        UUID withdrawalId = withdrawalId(p.idempotencyKey());
        List<Map<String, Object>> local = withdrawalId == null ? List.of() : jdbc.queryForList(
                "select status, amount from withdrawal where id = ?", withdrawalId);
        if (local.isEmpty()) {
            if (p.createdAt() != null && p.createdAt().isAfter(now.minus(UNMATCHED_GRACE))) return 0;
            return flag(day, "PAYOUT_UNMATCHED", p.payoutId(), detail("idempotencyKey", p.idempotencyKey(),
                    "amount", p.amount(), "providerStatus", p.status()));
        }
        String status = (String) local.get(0).get("status");
        BigDecimal ours = (BigDecimal) local.get(0).get("amount");
        int found = 0;
        if (!statusAgrees(p.status(), status)) {
            found += flag(day, "PAYOUT_MISMATCH", p.payoutId(), detail("withdrawalId", withdrawalId,
                    "providerStatus", p.status(), "localStatus", status));
        }
        if (p.amount() == null || ours.compareTo(p.amount()) != 0) {
            found += flag(day, "PAYOUT_AMOUNT_MISMATCH", p.payoutId(), detail("withdrawalId", withdrawalId,
                    "providerAmount", p.amount(), "localAmount", ours));
        }
        return found;
    }

    private int checkProcessedWithdrawals(LocalDate day, Set<String> providerIds) {
        Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
        List<Map<String, Object>> processed = jdbc.queryForList("select id, provider_payout_id, amount from withdrawal "
                + "where status = 'PROCESSED' and provider_payout_id is not null and processed_at >= ? and processed_at < ?",
                java.sql.Timestamp.from(from), java.sql.Timestamp.from(from.plusSeconds(86400)));
        int found = 0;
        for (Map<String, Object> row : processed) {
            String payoutId = (String) row.get("provider_payout_id");
            if (!providerIds.contains(payoutId)) {
                found += flag(day, "PAYOUT_MISSING_AT_PROVIDER", payoutId,
                        detail("withdrawalId", row.get("id"), "amount", row.get("amount")));
            }
        }
        return found;
    }

    private static boolean statusAgrees(String provider, String local) {
        if (provider == null) return false;
        return switch (provider) {
            case "PROCESSED" -> "PROCESSED".equals(local);
            case "REVERSED" -> "REVERSED".equals(local);
            case "FAILED" -> "FAILED".equals(local);
            case "QUEUED", "PROCESSING" -> "PROCESSING".equals(local) || "REQUESTED".equals(local);
            default -> false;
        };
    }

    private static UUID withdrawalId(String key) {
        if (key == null) return null;
        try {
            return UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int flag(LocalDate day, String kind, String providerRef, Map<String, Object> detail) {
        log.error("Reconciliation issue {} for {} on {}: {}", kind, providerRef, day, detail);
        issues.record(day, kind, providerRef, detail);
        return 1;
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1] instanceof BigDecimal b ? b.toPlainString() : kv[i + 1].toString());
            }
        }
        return m;
    }
}
