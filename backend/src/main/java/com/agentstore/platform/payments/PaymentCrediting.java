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

/** Turns a verified, amount-checked captured payment into money: the ledger post, the wallet dual-write and the order. */
@Component
public class PaymentCrediting {

    private static final Logger log = LoggerFactory.getLogger(PaymentCrediting.class);

    public enum Result {
        /** Posted, wallet and order updated, event published. */
        CREDITED,
        /** {@code payment:{paymentId}} was already posted (the same payment under another event id); nothing done. */
        ALREADY_CREDITED,
        /** The order is already PAID by a different payment; recorded as a reconciliation issue, nothing credited. */
        REFUSED
    }

    private final WalletRepository walletRepository;
    private final LedgerAccounts ledgerAccounts;
    private final LedgerService ledgerService;
    private final LedgerTransactionRepository transactions;
    private final PaymentOrderRepository orders;
    private final DomainEventPublisher events;
    private final ReconciliationIssues issues;

    @PersistenceContext
    private EntityManager entityManager;

    public PaymentCrediting(WalletRepository walletRepository, LedgerAccounts ledgerAccounts,
                            LedgerService ledgerService, LedgerTransactionRepository transactions,
                            PaymentOrderRepository orders, DomainEventPublisher events, ReconciliationIssues issues) {
        this.walletRepository = walletRepository;
        this.ledgerAccounts = ledgerAccounts;
        this.ledgerService = ledgerService;
        this.transactions = transactions;
        this.orders = orders;
        this.events = events;
        this.issues = issues;
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
        if (order.getStatus() == PaymentOrder.Status.PAID && !paymentId.equals(order.getProviderPaymentId())) {
            log.error("Order {} is already paid by {}; second payment {} of {} not credited", order.getId(),
                    order.getProviderPaymentId(), paymentId, amount.toPlainString());
            issues.record("DUPLICATE_PAYMENT", paymentId, Map.<String, Object>of("paymentOrderId", order.getId().toString(),
                    "paidBy", String.valueOf(order.getProviderPaymentId()), "amount", amount.toPlainString()));
            return Result.REFUSED;
        }

        var walletAccount = ledgerAccounts.buyerWallet(wallet);   // before the balance changes: it seeds the opening
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("paymentOrderId", order.getId().toString());
        reference.put("method", order.getMethod().name());
        LedgerTransaction posted = ledgerService.post(LedgerTransactionType.TOPUP, key, null, order.getOrgId(),
                reference, List.of(new Posting(clearing, amount.negate()), new Posting(walletAccount, amount)));
        if (posted.getType() != LedgerTransactionType.TOPUP || !order.getOrgId().equals(posted.getOrgId())) {
            throw new IllegalStateException("Idempotency key " + key + " belongs to another transaction");
        }

        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        order.setStatus(PaymentOrder.Status.PAID);
        order.setProviderPaymentId(paymentId);
        order.setPaidAt(Instant.now());
        orders.save(order);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentOrderId", order.getId().toString());
        payload.put("paymentId", paymentId);
        payload.put("amount", amount.toPlainString());
        payload.put("method", order.getMethod().name());
        events.publish("payment.topup_paid", order.getOrgId(), order.getId(), payload);
        return Result.CREDITED;
    }

    /** Bill payments arrive in Task 6; until then the webhook rolls back so the provider retries it later. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Result creditBill(PaymentOrder order, String paymentId, BigDecimal amount) {
        throw new UnsupportedOperationException("Bill payments are not credited yet (order " + order.getId() + ")");
    }
}
