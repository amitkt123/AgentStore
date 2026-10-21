package com.agentstore.platform.payments;

import com.agentstore.platform.events.DomainEventPublisher;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccounts;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.ledger.LedgerTransaction;
import com.agentstore.platform.ledger.LedgerTransactionRepository;
import com.agentstore.platform.ledger.LedgerTransactionType;
import com.agentstore.platform.ledger.Posting;
import com.agentstore.platform.ledger.Wallet;
import com.agentstore.platform.ledger.WalletRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Turns a verified, amount-checked captured payment into money: the ledger post, the wallet dual-write and the order. */
@Component
public class PaymentCrediting {

    private static final Logger log = LoggerFactory.getLogger(PaymentCrediting.class);

    public enum Result {
        /** Posted, wallet and order updated, event published. */
        CREDITED,
        /** {@code payment:{paymentId}} was already posted (the same payment under another event id); nothing done. */
        ALREADY_CREDITED,
        /** A bill order already PAID by a different payment; recorded as a reconciliation issue, nothing credited. */
        REFUSED
    }

    private final WalletRepository walletRepository;
    private final LedgerAccounts ledgerAccounts;
    private final LedgerService ledgerService;
    private final LedgerTransactionRepository transactions;
    private final PaymentOrderRepository orders;
    private final DomainEventPublisher events;
    private final ReconciliationIssues issues;
    private final PaymentProvider provider;
    private final BuyerBillRepository bills;

    @PersistenceContext
    private EntityManager entityManager;

    public PaymentCrediting(WalletRepository walletRepository, LedgerAccounts ledgerAccounts,
                            LedgerService ledgerService, LedgerTransactionRepository transactions,
                            PaymentOrderRepository orders, DomainEventPublisher events, ReconciliationIssues issues,
                            PaymentProvider provider, BuyerBillRepository bills) {
        this.walletRepository = walletRepository;
        this.ledgerAccounts = ledgerAccounts;
        this.ledgerService = ledgerService;
        this.transactions = transactions;
        this.orders = orders;
        this.events = events;
        this.issues = issues;
        this.provider = provider;
        this.bills = bills;
    }

    /**
     * Credits a top-up once per provider payment id. Lock order: the wallet row, then the order row, then (inside
     * {@code post}) the balance rows. The credit lands in the wallet even if the org has since switched to POSTPAID:
     * the money was paid and must not vanish; it can be refunded.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result creditTopUp(PaymentOrder order, String paymentId, BigDecimal amount) {
        String key = "payment:" + paymentId;
        // Resolve platform accounts before taking locks; buyerWallet may post an opening balance against EXTERNAL.
        var clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYMENT_CLEARING);
        ledgerAccounts.platformAccount(LedgerAccountKind.EXTERNAL);

        walletRepository.insertEmptyIfMissing(order.getOrgId());
        Wallet wallet = walletRepository.findByOrgId(order.getOrgId()).orElseThrow();
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);

        // Under the wallet lock a concurrent credit of the same payment has either committed (seen here) or waits.
        if (transactions.findByIdempotencyKey(key).isPresent()) {
            log.info("Payment {} already credited; order {} left as is", paymentId, order.getId());
            return Result.ALREADY_CREDITED;
        }
        // A second, distinct payment on an order that is already PAID is real money: credit it as its own top-up
        // order, so it stays refundable to its own source.
        boolean secondPayment = order.getStatus() == PaymentOrder.Status.PAID
                && !paymentId.equals(order.getProviderPaymentId());
        PaymentOrder target = secondPayment ? duplicateOrder(order, paymentId, amount) : order;

        var walletAccount = ledgerAccounts.buyerWallet(wallet);   // before the balance changes: it seeds the opening
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("paymentOrderId", target.getId().toString());
        reference.put("method", target.getMethod().name());
        LedgerService.PostResult posted = ledgerService.postWithResult(LedgerTransactionType.TOPUP, key, null,
                target.getOrgId(), reference,
                List.of(new Posting(clearing, amount.negate()), new Posting(walletAccount, amount)));
        LedgerTransaction transaction = posted.transaction();
        if (transaction.getType() != LedgerTransactionType.TOPUP || !target.getOrgId().equals(transaction.getOrgId())) {
            throw new IllegalStateException("Idempotency key " + key + " belongs to another transaction");
        }
        if (!posted.created()) {
            // Unreachable while the lock-held check above stands; never dual-write on a replayed post.
            if (secondPayment) {
                // Roll back the order row just inserted; the provider's retry then takes the ALREADY_CREDITED path.
                throw new IllegalStateException("Payment " + paymentId + " was posted concurrently; retry");
            }
            log.warn("Payment {} replayed inside post; wallet and order {} left as is", paymentId, order.getId());
            return Result.ALREADY_CREDITED;
        }

        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        if (!secondPayment) {
            order.setStatus(PaymentOrder.Status.PAID);
            order.setProviderPaymentId(paymentId);
            order.setPaidAt(Instant.now());
            orders.save(order);
        } else {
            log.warn("Order {} already paid by {}; second payment {} of {} credited as order {}", order.getId(),
                    order.getProviderPaymentId(), paymentId, amount.toPlainString(), target.getId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentOrderId", target.getId().toString());
        payload.put("paymentId", paymentId);
        payload.put("amount", amount.toPlainString());
        payload.put("method", target.getMethod().name());
        events.publish("payment.topup_paid", target.getOrgId(), target.getId(), payload);
        return Result.CREDITED;
    }

    /** A PAID top-up order of its own for a second payment that arrived on {@code paid}. */
    private PaymentOrder duplicateOrder(PaymentOrder paid, String paymentId, BigDecimal amount) {
        PaymentOrder dup = new PaymentOrder();
        dup.setOrgId(paid.getOrgId());
        dup.setPurpose(PaymentOrder.Purpose.TOPUP);
        dup.setMethod(paid.getMethod());
        dup.setAmount(amount);
        dup.setStatus(PaymentOrder.Status.PAID);
        dup.setProvider(provider.name());
        dup.setProviderOrderId("dup:" + paymentId);
        dup.setProviderPaymentId(paymentId);
        dup.setPaidAt(Instant.now());
        return orders.saveAndFlush(dup);
    }

    /**
     * Pays a bill once per provider payment id: BILL_PAYMENT {@code PAYMENT_CLEARING -A, BUYER_RECEIVABLE +A}, then
     * the bill and order PAID. The wallet balance is never touched. Lock order: the wallet row (so the overdue check
     * in {@code reserve} and this payment are serialised), the order row, the bill row, then (inside {@code post})
     * the balance rows. A second, distinct payment for a bill already PAID (on this order or another) is recorded
     * as {@code DUPLICATE_PAYMENT} and not credited; the money waits in PAYMENT_CLEARING for ops to refund.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result creditBill(PaymentOrder order, String paymentId, BigDecimal amount) {
        String key = "payment:" + paymentId;
        UUID orgId = order.getOrgId();
        // Resolve accounts before taking locks (V46 creates the receivable with the org).
        var clearing = ledgerAccounts.platformAccount(LedgerAccountKind.PAYMENT_CLEARING);
        var receivable = ledgerAccounts.orgAccount(orgId, LedgerAccountKind.BUYER_RECEIVABLE);

        walletRepository.insertEmptyIfMissing(orgId);
        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(order, LockModeType.PESSIMISTIC_WRITE);
        BuyerBill bill = order.getBillId() == null ? null : bills.findById(order.getBillId()).orElse(null);
        if (bill != null) {
            entityManager.refresh(bill, LockModeType.PESSIMISTIC_WRITE);
        }

        if (transactions.findByIdempotencyKey(key).isPresent()) {
            log.info("Payment {} already credited; bill order {} left as is", paymentId, order.getId());
            return Result.ALREADY_CREDITED;
        }
        if (order.getStatus() == PaymentOrder.Status.PAID && !paymentId.equals(order.getProviderPaymentId())) {
            return duplicateBillPayment(order, paymentId, amount, String.valueOf(order.getProviderPaymentId()));
        }
        if (bill == null || !orgId.equals(bill.getOrgId())) {
            log.error("Bill order {} has no bill of its org; payment {} of {} not credited", order.getId(), paymentId,
                    amount.toPlainString());
            issues.record("BILL_NOT_FOUND", paymentId, Map.<String, Object>of("paymentOrderId",
                    order.getId().toString(), "billId", String.valueOf(order.getBillId()),
                    "amount", amount.toPlainString()));
            return Result.REFUSED;
        }
        if (bill.getStatus() == BuyerBill.Status.PAID) {
            return duplicateBillPayment(order, paymentId, amount, "bill:" + bill.getId());
        }
        if (amount.compareTo(bill.getAmount()) != 0) {
            log.error("Payment {} of {} does not match bill {} amount {}; not credited", paymentId,
                    amount.toPlainString(), bill.getId(), bill.getAmount().toPlainString());
            issues.record("AMOUNT_MISMATCH", paymentId, Map.<String, Object>of("paymentOrderId",
                    order.getId().toString(), "billId", bill.getId().toString(),
                    "billAmount", bill.getAmount().toPlainString(), "paidAmount", amount.toPlainString()));
            return Result.REFUSED;
        }

        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("paymentOrderId", order.getId().toString());
        reference.put("billId", bill.getId().toString());
        reference.put("period", bill.getPeriod().trim());
        reference.put("method", order.getMethod().name());
        LedgerService.PostResult posted = ledgerService.postWithResult(LedgerTransactionType.BILL_PAYMENT, key, null,
                orgId, reference, List.of(new Posting(clearing, amount.negate()), new Posting(receivable, amount)));
        LedgerTransaction transaction = posted.transaction();
        if (transaction.getType() != LedgerTransactionType.BILL_PAYMENT || !orgId.equals(transaction.getOrgId())) {
            throw new IllegalStateException("Idempotency key " + key + " belongs to another transaction");
        }
        if (!posted.created()) {
            // Unreachable while the lock-held check above stands; never update the bill on a replayed post.
            log.warn("Payment {} replayed inside post; bill {} and order {} left as is", paymentId, bill.getId(),
                    order.getId());
            return Result.ALREADY_CREDITED;
        }

        Instant now = Instant.now();
        bill.setStatus(BuyerBill.Status.PAID);
        bill.setPaidAt(now);
        bills.save(bill);
        order.setStatus(PaymentOrder.Status.PAID);
        order.setProviderPaymentId(paymentId);
        order.setPaidAt(now);
        orders.save(order);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("billId", bill.getId().toString());
        payload.put("period", bill.getPeriod().trim());
        payload.put("paymentOrderId", order.getId().toString());
        payload.put("paymentId", paymentId);
        payload.put("amount", amount.toPlainString());
        payload.put("method", order.getMethod().name());
        events.publish("payment.bill_paid", orgId, bill.getId(), payload);
        return Result.CREDITED;
    }

    private Result duplicateBillPayment(PaymentOrder order, String paymentId, BigDecimal amount, String paidBy) {
        log.error("Bill order {} is already paid by {}; second payment {} of {} not credited", order.getId(), paidBy,
                paymentId, amount.toPlainString());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paymentOrderId", order.getId().toString());
        detail.put("billId", String.valueOf(order.getBillId()));
        detail.put("paidBy", paidBy);
        detail.put("amount", amount.toPlainString());
        issues.record("DUPLICATE_PAYMENT", paymentId, detail);
        return Result.REFUSED;
    }
}
