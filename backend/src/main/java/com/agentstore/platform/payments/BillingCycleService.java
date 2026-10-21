package com.agentstore.platform.payments;

import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.LedgerPostingRepository;
import com.agentstore.platform.ledger.MoneyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Monthly bills for postpaid orgs, and whether an unpaid one is past due. */
@Service
public class BillingCycleService {

    private static final Logger log = LoggerFactory.getLogger(BillingCycleService.class);

    private final BuyerBillRepository bills;
    private final LedgerPostingRepository postings;
    private final PaymentProperties props;
    private final MoneyProperties moneyProps;
    private final JdbcTemplate jdbc;

    public BillingCycleService(BuyerBillRepository bills, LedgerPostingRepository postings, PaymentProperties props,
                               MoneyProperties moneyProps, JdbcTemplate jdbc) {
        this.bills = bills;
        this.postings = postings;
        this.props = props;
        this.moneyProps = moneyProps;
        this.jdbc = jdbc;
    }

    /**
     * Issues one bill per POSTPAID org for what its runs were charged in {@code period} (UTC), due
     * {@code bill-due-days} after the period ends. Orgs charged nothing get no bill. Idempotent on (org, period);
     * each bill commits on its own, so a rerun after a failure picks up the rest.
     *
     * @return the number of bills created by this call
     */
    public int issueBills(YearMonth period) {
        Instant from = period.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = period.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant dueAt = to.plus(Duration.ofDays(props.billDueDays()));
        String periodText = period.toString();   // YYYY-MM
        List<UUID> orgs = jdbc.queryForList("select id from org where type = 'BUYER' and coalesce(billing_mode, ?) = ?",
                UUID.class, moneyProps.billingMode().name(), BillingMode.POSTPAID.name());
        int created = 0;
        for (UUID orgId : orgs) {
            BigDecimal amount = postings.receivableRunChargesBetween(orgId, from, to);
            if (amount.signum() <= 0) continue;
            created += jdbc.update("insert into buyer_bill (id, org_id, period, amount, status, due_at) "
                            + "values (gen_random_uuid(), ?, ?, ?, 'OPEN', ?) on conflict (org_id, period) do nothing",
                    orgId, periodText, amount, Timestamp.from(dueAt));
        }
        log.info("Issued {} bills for {}", created, periodText);
        return created;
    }

    /**
     * True when the org has an OPEN or OVERDUE bill past its due date. Reads status and time, so it holds whether
     * or not the maintenance job has marked the bill OVERDUE yet. Stable under the org's wallet lock, which bill
     * payments take before setting a bill PAID.
     */
    @Transactional(readOnly = true)
    public boolean hasOverdueBill(UUID orgId) {
        return bills.hasUnpaidDueBefore(orgId, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<BillView> bills(UUID orgId) {
        return bills.findByOrgIdOrderByPeriodDesc(orgId).stream().map(BillView::of).toList();
    }

    /** The org's own bill if it can still be paid (OPEN or OVERDUE), else 409 BILL_NOT_PAYABLE. */
    @Transactional(readOnly = true)
    public BuyerBill payableBill(UUID orgId, UUID billId) {
        return bills.findById(billId)
                .filter(b -> orgId.equals(b.getOrgId()) && b.getStatus() != BuyerBill.Status.PAID)
                .orElseThrow(() -> new PaymentApiException(HttpStatus.CONFLICT,
                        "BILL_NOT_PAYABLE", "This bill is already paid or does not exist"));
    }

    /** OPEN bills past their due date become OVERDUE. */
    @Transactional
    public int markOverdueBills() {
        int marked = bills.markOverdue(Instant.now());
        if (marked > 0) log.info("Marked {} bills overdue", marked);
        return marked;
    }
}
