package com.agentstore.platform.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies a captured provider payment we never credited (a MISSING_LOCALLY issue, e.g. its webhook was lost), through
 * the same {@link PaymentCrediting} path the webhook uses, so it is idempotent on {@code payment:{paymentId}}. The
 * provider is asked first, outside any transaction; only a captured payment on an order (or virtual account) we know,
 * for that order's amount, is credited.
 */
@Service
public class PaymentRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryService.class);

    public record Applied(String paymentId, UUID paymentOrderId, PaymentCrediting.Result result, int issuesResolved) {}

    private final PaymentProvider provider;
    private final PaymentOrderRepository orders;
    private final VirtualAccountRepository virtualAccounts;
    private final PaymentCrediting crediting;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public PaymentRecoveryService(PaymentProvider provider, PaymentOrderRepository orders,
                                  VirtualAccountRepository virtualAccounts, PaymentCrediting crediting,
                                  JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
        this.provider = provider;
        this.orders = orders;
        this.virtualAccounts = virtualAccounts;
        this.crediting = crediting;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Applied apply(String paymentId) {
        PaymentProvider.ProviderPayment payment;
        try {
            payment = provider.fetchPayment(paymentId).orElseThrow(() -> new PaymentApiException(HttpStatus.NOT_FOUND,
                    "PAYMENT_NOT_FOUND", "The provider does not know this payment"));
        } catch (PaymentProviderUnavailableException e) {
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_UNAVAILABLE",
                    "The payment provider is unavailable; try again");
        }
        if (!"captured".equals(payment.status())) {
            throw new PaymentApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_CAPTURED",
                    "Only a captured payment can be applied");
        }
        BigDecimal amount = payment.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new PaymentApiException(HttpStatus.CONFLICT, "AMOUNT_MISMATCH", "The payment has no positive amount");
        }
        Applied applied = tx.execute(status -> {
            PaymentOrder order = orderFor(payment, amount);
            PaymentCrediting.Result result = crediting.credit(order, paymentId, order.getAmount());
            int resolved = result == PaymentCrediting.Result.REFUSED ? 0 : resolveMissingLocally(paymentId, result);
            return new Applied(paymentId, order.getId(), result, resolved);
        });
        log.info("Applied provider payment {} to order {}: {}", paymentId, applied.paymentOrderId(), applied.result());
        return applied;
    }

    private PaymentOrder orderFor(PaymentProvider.ProviderPayment payment, BigDecimal amount) {
        if (payment.orderId() != null) {
            PaymentOrder order = orders.findByProviderAndProviderOrderId(provider.name(), payment.orderId())
                    .orElseThrow(PaymentRecoveryService::unknownOrder);
            if (amount.compareTo(order.getAmount()) != 0) {
                throw new PaymentApiException(HttpStatus.CONFLICT, "AMOUNT_MISMATCH",
                        "The payment amount does not match its order");
            }
            return order;
        }
        if (payment.virtualAccountId() != null) {
            Optional<VirtualAccount> va = virtualAccounts.findByProviderAndProviderVaId(provider.name(),
                    payment.virtualAccountId());
            if (va.isPresent()) return crediting.bankTransferOrder(va.get().getOrgId(), payment.paymentId(), amount);
        }
        throw unknownOrder();
    }

    private static PaymentApiException unknownOrder() {
        return new PaymentApiException(HttpStatus.CONFLICT, "ORDER_UNKNOWN",
                "The payment is for no order or virtual account of ours");
    }

    private int resolveMissingLocally(String paymentId, PaymentCrediting.Result result) {
        String note;
        try {
            note = mapper.writeValueAsString(Map.of("resolutionNote", "Applied from the provider: " + result.name()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return jdbc.update("update reconciliation_issue set resolved_at = now(), "
                + "detail = coalesce(detail, '{}'::jsonb) || ?::jsonb "
                + "where kind = 'MISSING_LOCALLY' and provider_ref = ? and resolved_at is null", note, paymentId);
    }
}
