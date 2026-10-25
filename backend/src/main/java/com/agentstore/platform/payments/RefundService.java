package com.agentstore.platform.payments;

import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.LedgerTransactionType;
import com.agentstore.platform.ledger.Posting;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Refunds wallet money back to the payments it came from, newest payment first.
 * <p>
 * Money moves in the ledger before the provider is asked: {@link #request} posts {@code REFUND} per part
 * ({@code refund:{partId}}) and dual-writes {@code wallet.balance} and the order's {@code refunded_amount} in one
 * transaction under the wallet lock, commits, and only then calls the provider for each part (never inside a DB
 * transaction). A part the provider definitely rejects, or later reports FAILED, is reversed once
 * ({@code refund-failed:{partId}}). A part whose call timed out stays PENDING; {@link #submitPending} looks it up by
 * its receipt (the part id) before asking again, so a refund the provider did make is never made twice.
 * <p>
 * Lock order everywhere: the wallet row, then order or part rows, then (inside {@code post}) balance rows.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /**
     * The lease a submitter takes on a PENDING part (see {@link #claim}): no one else submits the part until it has
     * been untouched this long. Provider HTTP timeouts must stay well below this, or a still-running call could
     * be overtaken by a retry.
     */
    static final Duration RETRY_GRACE = Duration.ofMinutes(5);
    /** A retry run stops taking new parts after this long, well inside the job's ShedLock lockAtMostFor (PT10M). */
    static final Duration JOB_BUDGET = Duration.ofMinutes(4);
    /** A PROCESSING part with no webhook for this long is looked up at the provider (lost or early webhook). */
    static final Duration POLL_AFTER = Duration.ofHours(1);
    private static final int RETRY_BATCH = 100;

    /** One slice of a refund: {@code amount} back to the payment of order {@code paymentOrderId}. */
    public record Part(UUID paymentOrderId, PaymentMethod method, Instant paidAt, BigDecimal amount) {}

    /** What {@link #requestOrExisting} did: {@code created} is false when the request id was already used. */
    public record Requested(RefundView refund, boolean created) {}

    private record Created(UUID refundId, List<UUID> partIds, boolean created) {}

    private record Submission(UUID partId, String paymentId, BigDecimal amount) {}

    private final PaymentProvider provider;
    private final PaymentProperties props;
    private final PaymentOrderRepository orders;
    private final BuyerRefundRepository refunds;
    private final BuyerRefundPartRepository parts;
    private final WalletRepository walletRepository;
    private final LedgerAccounts ledgerAccounts;
    private final LedgerService ledgerService;
    private final DomainEventPublisher events;
    private final ReconciliationIssues issues;
    private final TransactionTemplate tx;
    private final OrgRepository orgRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public RefundService(PaymentProvider provider, PaymentProperties props, PaymentOrderRepository orders,
                         BuyerRefundRepository refunds, BuyerRefundPartRepository parts,
                         WalletRepository walletRepository, LedgerAccounts ledgerAccounts, LedgerService ledgerService,
                         DomainEventPublisher events, ReconciliationIssues issues, TransactionTemplate tx,
                         OrgRepository orgRepository) {
        this.orgRepository = orgRepository;
        this.provider = provider;
        this.props = props;
        this.orders = orders;
        this.refunds = refunds;
        this.parts = parts;
        this.walletRepository = walletRepository;
        this.ledgerAccounts = ledgerAccounts;
        this.ledgerService = ledgerService;
        this.events = events;
        this.issues = issues;
        this.tx = tx;
    }

    // ---- reading ----

    /** How {@code amount} would split across the org's refundable top-ups now (less, if they hold less). */
    @Transactional(readOnly = true)
    public List<Part> plan(UUID orgId, BigDecimal amount) {
        return walk(orders.refundableTopUps(orgId, windowStart()), amount);
    }

    /** {@code min(balance − reserved, Σ eligible (amount − refunded_amount))}, floored at 0. */
    @Transactional(readOnly = true)
    public BigDecimal refundable(UUID orgId) {
        Optional<Wallet> wallet = walletRepository.findReadOnlyByOrgId(orgId);
        if (wallet.isEmpty()) return BigDecimal.ZERO.setScale(2);
        return refundable(wallet.get(), orders.refundableTopUps(orgId, windowStart()));
    }

    public List<RefundView> recent(UUID orgId) {
        return tx.execute(s -> refunds.findTop50ByOrgIdOrderByCreatedAtDesc(orgId).stream().map(this::view).toList());
    }

    // ---- requesting ----

    public RefundView request(UUID orgId, UUID userId, String requestId, BigDecimal amount) {
        return requestOrExisting(orgId, userId, requestId, amount).refund();
    }

    /**
     * Creates and submits a refund, or returns the one already created under {@code requestId}.
     *
     * @throws PaymentApiException AMOUNT_INVALID, MONEY_FROZEN (409), NOTHING_REFUNDABLE or REFUND_EXCEEDS_REFUNDABLE
     */
    public Requested requestOrExisting(UUID orgId, UUID userId, String requestId, BigDecimal amount) {
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID",
                    "requestId is required (at most 128 characters)");
        }
        BigDecimal a = validAmount(amount);
        Optional<BuyerRefund> existing = refunds.findByOrgIdAndRequestId(orgId, requestId);
        if (existing.isPresent()) {
            return new Requested(tx.execute(s -> view(refunds.findById(existing.get().getId()).orElseThrow())), false);
        }
        Created c = tx.execute(s -> create(orgId, userId, requestId, a));
        if (c.created()) {
            // The money has left the wallet in the ledger; now ask the provider, outside any transaction.
            for (UUID partId : c.partIds()) {
                submitQuietly(partId, true);
            }
        }
        return new Requested(tx.execute(s -> view(refunds.findById(c.refundId()).orElseThrow())), c.created());
    }

    /** Validates {@code amount} against the refundable total without changing anything (for the preview). */
    public void checkRequestable(UUID orgId, BigDecimal amount) {
        rejectIfNotRefundable(refundable(orgId), validAmount(amount));
    }

    private Created create(UUID orgId, UUID userId, String requestId, BigDecimal amount) {
        // Resolve platform accounts before taking locks; buyerWallet may post an opening balance against EXTERNAL.
        UUID clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYMENT_CLEARING);
        ledgerAccounts.platformAccount(LedgerAccountKind.EXTERNAL);
        Wallet wallet = lockWallet(orgId);

        // Under the wallet lock a concurrent request with the same id has either committed (seen here) or waits.
        Optional<BuyerRefund> existing = refunds.findByOrgIdAndRequestId(orgId, requestId);
        if (existing.isPresent()) {
            return new Created(existing.get().getId(), List.of(), false);
        }

        // A frozen org can take money in but cannot move any out.
        if (orgRepository.findById(orgId).map(o -> o.getMoneyFrozenAt() != null).orElse(false)) {
            throw new PaymentApiException(HttpStatus.CONFLICT, "MONEY_FROZEN",
                    "Money movement is frozen for this organisation");
        }

        List<PaymentOrder> eligible = orders.refundableTopUps(orgId, windowStart());
        for (PaymentOrder o : eligible) {
            entityManager.refresh(o, LockModeType.PESSIMISTIC_WRITE);
        }
        rejectIfNotRefundable(refundable(wallet, eligible), amount);
        List<Part> plan = walk(eligible, amount);
        Map<UUID, PaymentOrder> byId = eligible.stream().collect(Collectors.toMap(PaymentOrder::getId, Function.identity()));

        BuyerRefund refund = new BuyerRefund();
        refund.setOrgId(orgId);
        refund.setRequestId(requestId);
        refund.setAmount(amount);
        refund.setStatus(BuyerRefund.Status.PROCESSING);
        refund.setRequestedByUserId(userId);
        refund = refunds.saveAndFlush(refund);

        UUID walletAccount = ledgerAccounts.buyerWallet(wallet);   // before the balance changes
        Instant base = Instant.now();
        List<UUID> partIds = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            Part p = plan.get(i);
            BuyerRefundPart part = new BuyerRefundPart();
            part.setRefundId(refund.getId());
            part.setPaymentOrderId(p.paymentOrderId());
            part.setAmount(p.amount());
            part.setStatus(BuyerRefundPart.Status.PENDING);
            // Stepped so the parts read back in plan order (Postgres keeps microseconds).
            part.setCreatedAt(base.plusNanos(1_000L * i));
            part = parts.saveAndFlush(part);

            Map<String, Object> reference = new LinkedHashMap<>();
            reference.put("refundId", refund.getId().toString());
            reference.put("refundPartId", part.getId().toString());
            reference.put("paymentOrderId", p.paymentOrderId().toString());
            LedgerService.PostResult posted = ledgerService.postWithResult(LedgerTransactionType.REFUND,
                    "refund:" + part.getId(), null, orgId, reference,
                    List.of(new Posting(walletAccount, p.amount().negate()), new Posting(clearing, p.amount())));
            if (!posted.created()) {
                // A fresh part id cannot have been posted; never dual-write on a replay.
                throw new IllegalStateException("Refund part " + part.getId() + " was already posted");
            }
            wallet.setBalance(wallet.getBalance().subtract(p.amount()));
            PaymentOrder order = byId.get(p.paymentOrderId());
            order.setRefundedAmount(order.getRefundedAmount().add(p.amount()));
            orders.save(order);
            partIds.add(part.getId());
        }
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        log.info("Refund {} of {} for org {} split into {} part(s)", refund.getId(), amount.toPlainString(), orgId,
                partIds.size());
        return new Created(refund.getId(), partIds, true);
    }

    // ---- submitting ----

    /**
     * Run by {@link RefundRetryJob}: resubmits PENDING parts untouched for {@link #RETRY_GRACE}, and asks the
     * provider about PROCESSING parts that have had no webhook for {@link #POLL_AFTER}.
     */
    public void submitPending() {
        Instant now = Instant.now();
        Instant stopAt = now.plus(JOB_BUDGET);
        for (UUID partId : parts.pendingIdsBefore(now.minus(RETRY_GRACE), PageRequest.of(0, RETRY_BATCH))) {
            if (Instant.now().isAfter(stopAt)) {
                log.warn("Refund retry run out of time; remaining parts wait for the next run");
                return;
            }
            submitQuietly(partId, false);
        }
        for (UUID partId : parts.processingIdsBefore(now.minus(POLL_AFTER), PageRequest.of(0, RETRY_BATCH))) {
            if (Instant.now().isAfter(stopAt)) {
                log.warn("Refund poll run out of time; remaining parts wait for the next run");
                return;
            }
            try {
                poll(partId);
            } catch (RuntimeException e) {
                log.error("Polling refund part {} failed; left for the next run", partId, e);
            }
        }
    }

    /** Applies the provider's current answer for a PROCESSING part; outside any transaction. */
    private void poll(UUID partId) {
        Submission s = tx.execute(st -> {
            BuyerRefundPart part = parts.findById(partId).orElseThrow();
            if (part.getStatus() != BuyerRefundPart.Status.PROCESSING) return null;
            PaymentOrder order = orders.findById(part.getPaymentOrderId()).orElseThrow();
            return new Submission(partId, order.getProviderPaymentId(), part.getAmount());
        });
        if (s == null) return;
        Optional<PaymentProvider.ProviderRefund> found;
        try {
            found = provider.findRefund(s.paymentId(), partId.toString());
        } catch (PaymentProviderRejectedException | PaymentProviderUnavailableException e) {
            log.warn("Looking up refund part {} failed; polled again later: {}", partId, e.getMessage());
            return;
        }
        String status = found.map(r -> r.status() == null ? "" : r.status().toUpperCase()).orElse("");
        switch (status) {
            case "PROCESSED" -> tx.executeWithoutResult(st -> markDone(partId, found.get().refundId()));
            case "FAILED" -> tx.executeWithoutResult(st -> failPart(partId, "Provider failed the refund"));
            default -> {
                if (found.isEmpty()) {
                    log.error("Refund part {} is PROCESSING but the provider has no refund for it", partId);
                }
                tx.executeWithoutResult(st -> parts.touchProcessing(partId));
            }
        }
    }

    private void submitQuietly(UUID partId, boolean first) {
        try {
            submit(partId, first);
        } catch (RuntimeException e) {
            // The part stays PENDING; the retry job tries it again.
            log.error("Submitting refund part {} failed; left for retry", partId, e);
        }
    }

    /**
     * Retries one PENDING part whose lease has lapsed; a part another submitter holds is skipped. Must not run
     * inside a transaction.
     */
    public void submit(UUID partId) {
        submit(partId, false);
    }

    /**
     * Claims the part, then asks the provider, looking it up by receipt first so a refund the provider already made
     * (for example before a timeout) is not made again. {@code first} is the request's own submit right after commit.
     */
    private void submit(UUID partId, boolean first) {
        if (!claim(partId, first)) {
            log.info("Refund part {} is not PENDING or is held by another submitter; skipped", partId);
            return;
        }
        Submission s = tx.execute(st -> {
            BuyerRefundPart part = parts.findById(partId).orElseThrow();
            if (part.getStatus() != BuyerRefundPart.Status.PENDING) return null;
            PaymentOrder order = orders.findById(part.getPaymentOrderId()).orElseThrow();
            return new Submission(partId, order.getProviderPaymentId(), part.getAmount());
        });
        if (s == null) return;
        String receipt = partId.toString();

        Optional<PaymentProvider.ProviderRefund> found;
        try {
            found = provider.findRefund(s.paymentId(), receipt);
        } catch (PaymentProviderRejectedException | PaymentProviderUnavailableException e) {
            // Without knowing whether the refund exists, asking again could refund twice: wait for the next retry.
            log.warn("Looking up refund part {} failed; left PENDING: {}", partId, e.getMessage());
            return;
        }
        PaymentProvider.ProviderRefund result;
        if (found.isPresent()) {
            result = found.get();
        } else {
            try {
                result = provider.refund(s.paymentId(), s.amount(), receipt);
            } catch (PaymentProviderRejectedException e) {
                log.warn("Provider rejected refund part {}: {}", partId, e.getMessage());
                tx.executeWithoutResult(st -> failPartLocked(partId, "Provider rejected the refund: " + e.getMessage()));
                return;
            } catch (PaymentProviderUnavailableException e) {
                log.warn("Provider unavailable for refund part {}; left PENDING: {}", partId, e.getMessage());
                return;
            }
        }
        tx.executeWithoutResult(st -> recordSubmitted(partId, result));
    }

    /**
     * Atomically takes the lease on a PENDING part, committed before any provider call, so two submitters (the
     * request thread and a retry run, or two overlapping retry runs) cannot both call the provider. The first submit
     * claims a part never claimed before ({@code updated_at = created_at}); a retry claims one untouched for
     * {@link #RETRY_GRACE}. Both set {@code updated_at = now()}, which renews the lease.
     */
    private boolean claim(UUID partId, boolean first) {
        Integer changed = tx.execute(st -> first ? parts.claimFirstSubmit(partId)
                : parts.claimRetry(partId, Instant.now().minus(RETRY_GRACE)));
        return changed != null && changed == 1;
    }

    private void recordSubmitted(UUID partId, PaymentProvider.ProviderRefund result) {
        BuyerRefundPart part = lockPart(partId);
        if (part.getStatus() != BuyerRefundPart.Status.PENDING) {
            log.info("Refund part {} moved on to {} while submitting; left as is", partId, part.getStatus());
            return;
        }
        if (result.refundId() != null) part.setProviderRefundId(result.refundId());
        String status = result.status() == null ? "" : result.status().toUpperCase();
        switch (status) {
            case "PROCESSED" -> {
                part.setStatus(BuyerRefundPart.Status.DONE);
                parts.saveAndFlush(part);
                finish(part.getRefundId());
            }
            case "FAILED" -> {
                parts.saveAndFlush(part);
                fail(part, "Provider failed the refund");
            }
            default -> {
                part.setStatus(BuyerRefundPart.Status.PROCESSING);
                parts.saveAndFlush(part);
            }
        }
    }

    // ---- outcomes (webhooks and provider answers) ----

    /** The part's refund reached the buyer. Idempotent; a part already FAILED is flagged for ops, not changed. */
    @Transactional
    public void markDone(UUID partId, String providerRefundId) {
        BuyerRefundPart part = lockPart(partId);
        switch (part.getStatus()) {
            case DONE -> { }
            case FAILED -> {
                // We already returned this money to the wallet; the provider says it also reached the buyer.
                log.error("Refund part {} was reversed but provider {} reports it processed", partId, providerRefundId);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("refundPartId", partId.toString());
                detail.put("amount", part.getAmount().toPlainString());
                issues.record("REFUND_PROCESSED_AFTER_REVERSAL", providerRefundId == null ? partId.toString()
                        : providerRefundId, detail);
            }
            default -> {
                if (part.getProviderRefundId() == null && providerRefundId != null) {
                    part.setProviderRefundId(providerRefundId);
                }
                part.setStatus(BuyerRefundPart.Status.DONE);
                parts.saveAndFlush(part);
                finish(part.getRefundId());
            }
        }
    }

    /** The part's refund will not reach the buyer: put its money back in the wallet, once. */
    @Transactional
    public void failPart(UUID partId, String reason) {
        failPartLocked(partId, reason);
    }

    private void failPartLocked(UUID partId, String reason) {
        fail(lockPart(partId), reason);
    }

    /** {@code part} is locked, after its wallet, in the current transaction. */
    private void fail(BuyerRefundPart part, String reason) {
        if (part.getStatus() == BuyerRefundPart.Status.FAILED) return;
        if (part.getStatus() == BuyerRefundPart.Status.DONE) {
            log.error("Refund part {} is done; ignoring a later failure: {}", part.getId(), reason);
            return;
        }
        BuyerRefund refund = refunds.findById(part.getRefundId()).orElseThrow();
        Wallet wallet = walletRepository.findByOrgId(refund.getOrgId()).orElseThrow();   // locked by lockPart
        PaymentOrder order = orders.findById(part.getPaymentOrderId()).orElseThrow();
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);

        UUID clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYMENT_CLEARING);
        UUID walletAccount = ledgerAccounts.buyerWallet(wallet);
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("refundId", refund.getId().toString());
        reference.put("refundPartId", part.getId().toString());
        reference.put("paymentOrderId", order.getId().toString());
        LedgerService.PostResult posted = ledgerService.postWithResult(LedgerTransactionType.REVERSAL,
                "refund-failed:" + part.getId(), null, refund.getOrgId(), reference,
                List.of(new Posting(walletAccount, part.getAmount()), new Posting(clearing, part.getAmount().negate())));
        if (posted.created()) {
            wallet.setBalance(wallet.getBalance().add(part.getAmount()));
            wallet.setUpdatedAt(Instant.now());
            walletRepository.save(wallet);
            order.setRefundedAmount(order.getRefundedAmount().subtract(part.getAmount()));
            orders.save(order);
        } else {
            log.warn("Refund part {} reversal replayed; wallet and order left as is", part.getId());
        }
        part.setStatus(BuyerRefundPart.Status.FAILED);
        part.setFailureReason(reason == null || reason.isBlank() ? "Refund failed" : reason);
        parts.saveAndFlush(part);
        finish(refund.getId());
    }

    /** Once every part is final, settles the refund's status and, if any money went back, publishes the event. */
    private void finish(UUID refundId) {
        BuyerRefund refund = refunds.findById(refundId).orElseThrow();
        entityManager.refresh(refund, LockModeType.PESSIMISTIC_WRITE);
        if (refund.getStatus() != BuyerRefund.Status.PROCESSING) return;
        List<BuyerRefundPart> all = parts.findByRefundIdOrderByCreatedAtAscIdAsc(refundId);
        boolean allFinal = all.stream().allMatch(p -> p.getStatus() == BuyerRefundPart.Status.DONE
                || p.getStatus() == BuyerRefundPart.Status.FAILED);
        if (!allFinal) return;
        BigDecimal returned = all.stream().filter(p -> p.getStatus() == BuyerRefundPart.Status.DONE)
                .map(BuyerRefundPart::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long done = all.stream().filter(p -> p.getStatus() == BuyerRefundPart.Status.DONE).count();
        refund.setStatus(done == all.size() ? BuyerRefund.Status.DONE
                : done == 0 ? BuyerRefund.Status.FAILED : BuyerRefund.Status.PARTLY_FAILED);
        refunds.save(refund);
        if (done > 0) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("refundId", refund.getId().toString());
            payload.put("requestId", refund.getRequestId());
            payload.put("amount", refund.getAmount().toPlainString());
            payload.put("returned", returned.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
            payload.put("status", refund.getStatus().name());
            events.publish("payment.refund_done", refund.getOrgId(), refund.getId(), payload);
        }
    }

    /** The part for a provider refund id, if we have stored it. */
    @Transactional(readOnly = true)
    public Optional<UUID> partForProviderRefund(String providerRefundId) {
        if (providerRefundId == null || providerRefundId.isBlank()) return Optional.empty();
        return parts.findByProviderRefundId(providerRefundId).map(BuyerRefundPart::getId);
    }

    // ---- helpers ----

    private Wallet lockWallet(UUID orgId) {
        walletRepository.insertEmptyIfMissing(orgId);
        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        return wallet;
    }

    /** Locks the part's wallet, then the part, and returns the part as it stands under the locks. */
    private BuyerRefundPart lockPart(UUID partId) {
        BuyerRefundPart part = parts.findById(partId).orElseThrow();
        BuyerRefund refund = refunds.findById(part.getRefundId()).orElseThrow();
        lockWallet(refund.getOrgId());
        entityManager.refresh(part, LockModeType.PESSIMISTIC_WRITE);
        return part;
    }

    private Instant windowStart() {
        return Instant.now().minus(Duration.ofDays(props.refundWindowDays()));
    }

    private static BigDecimal remaining(PaymentOrder o) {
        return o.getAmount().subtract(o.getRefundedAmount());
    }

    private static BigDecimal refundable(Wallet wallet, List<PaymentOrder> eligible) {
        BigDecimal available = wallet.getBalance().subtract(wallet.getReserved() == null ? BigDecimal.ZERO
                : wallet.getReserved());
        BigDecimal fromPayments = eligible.stream().map(RefundService::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        return available.min(fromPayments).max(BigDecimal.ZERO).setScale(2, RoundingMode.UNNECESSARY);
    }

    private static List<Part> walk(List<PaymentOrder> eligible, BigDecimal amount) {
        List<Part> plan = new ArrayList<>();
        BigDecimal left = amount == null ? BigDecimal.ZERO : amount;
        for (PaymentOrder o : eligible) {
            if (left.signum() <= 0) break;
            BigDecimal take = remaining(o).min(left);
            if (take.signum() <= 0) continue;
            plan.add(new Part(o.getId(), o.getMethod(), o.getPaidAt(), take.setScale(2, RoundingMode.UNNECESSARY)));
            left = left.subtract(take);
        }
        return plan;
    }

    private static void rejectIfNotRefundable(BigDecimal refundable, BigDecimal amount) {
        if (refundable.signum() <= 0) {
            throw new PaymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NOTHING_REFUNDABLE",
                    "Nothing in this wallet can be refunded");
        }
        if (amount.compareTo(refundable) > 0) {
            throw new PaymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFUND_EXCEEDS_REFUNDABLE",
                    "At most " + refundable.toPlainString() + " can be refunded");
        }
    }

    static BigDecimal validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID",
                    "Amount must be positive with at most 2 decimal places");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private RefundView view(BuyerRefund refund) {
        List<BuyerRefundPart> ps = parts.findByRefundIdOrderByCreatedAtAscIdAsc(refund.getId());
        Map<UUID, PaymentOrder> byId = orders.findAllById(ps.stream().map(BuyerRefundPart::getPaymentOrderId).toList())
                .stream().collect(Collectors.toMap(PaymentOrder::getId, Function.identity()));
        List<RefundView.PartView> views = ps.stream().map(p -> new RefundView.PartView(p.getId(), p.getPaymentOrderId(),
                byId.get(p.getPaymentOrderId()).getMethod(), p.getAmount(), p.getStatus().name(),
                p.getFailureReason())).toList();
        return new RefundView(refund.getId(), refund.getAmount(), refund.getStatus().name(), views,
                refund.getCreatedAt());
    }
}
