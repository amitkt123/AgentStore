package com.agentstore.platform.payments;

import com.agentstore.platform.ledger.BillingMode;
import com.agentstore.platform.ledger.BillingModeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TopUpService {

    private static final Logger log = LoggerFactory.getLogger(TopUpService.class);

    private final PaymentProvider provider;
    private final PaymentProperties props;
    private final PaymentOrderRepository orders;
    private final BillingModeService billingModes;
    private final TransactionTemplate tx;

    public TopUpService(PaymentProvider provider, PaymentProperties props, PaymentOrderRepository orders,
                        BillingModeService billingModes, TransactionTemplate tx) {
        this.provider = provider;
        this.props = props;
        this.orders = orders;
        this.billingModes = billingModes;
        this.tx = tx;
    }

    public PaymentOptionsResponse options(UUID orgId) {
        List<PaymentMethod> methods = new ArrayList<>();
        for (PaymentMethod m : PaymentMethod.values()) {
            if (props.methods().contains(m)) methods.add(m);
        }
        return new PaymentOptionsResponse(provider.name(), methods, props.topupMin(), props.topupDailyLimit(),
                remainingToday(orgId));
    }

    public List<PaymentOrderView> recentTopUps(UUID orgId) {
        return orders.latestTopUps(orgId, PageRequest.of(0, 50)).stream().map(PaymentOrderView::of).toList();
    }

    /** 422 PAYMENT_METHOD_DISABLED unless the method is configured on. */
    public void checkMethod(PaymentMethod m) {
        if (m == null || !props.methods().contains(m)) {
            throw new PaymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_METHOD_DISABLED",
                    "This payment method is not available");
        }
    }

    public boolean bankTransferEnabled() {
        return props.methods().contains(PaymentMethod.BANK_TRANSFER);
    }

    public CheckoutResponse createTopUp(UUID orgId, UUID userId, BigDecimal amount, PaymentMethod method) {
        if (method == PaymentMethod.BANK_TRANSFER) {
            throw new PaymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_METHOD_DISABLED",
                    "Bank transfer top-ups use the account details, not an order");
        }
        checkMethod(method);
        if (billingModes.modeFor(orgId) == BillingMode.POSTPAID) {
            throw new PaymentApiException(HttpStatus.CONFLICT, "POSTPAID_NO_WALLET",
                    "Postpaid orgs have no wallet to top up; pay the bill instead");
        }
        validateAmount(amount);
        if (amount.compareTo(props.topupMin()) < 0) {
            throw new PaymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BELOW_MINIMUM",
                    "Minimum top-up is " + props.topupMin().toPlainString());
        }
        if (amount.compareTo(remainingToday(orgId)) > 0) {
            throw new PaymentApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DAILY_LIMIT_REACHED",
                    "Daily top-up limit reached");
        }
        return createOrder(orgId, userId, "TOPUP", null, amount, method);
    }

    /**
     * Saves the order, calls the provider outside any transaction, then records the provider's order id. If the
     * provider call fails the order is marked FAILED and 502 PROVIDER_UNAVAILABLE is returned.
     */
    public CheckoutResponse createOrder(UUID orgId, UUID userId, String purpose, UUID billId, BigDecimal amount,
                                        PaymentMethod method) {
        PaymentOrder saved = tx.execute(s -> {
            PaymentOrder o = new PaymentOrder();
            o.setOrgId(orgId);
            o.setPurpose(PaymentOrder.Purpose.valueOf(purpose));
            o.setBillId(billId);
            o.setMethod(method);
            o.setAmount(amount);
            o.setStatus(PaymentOrder.Status.CREATED);
            o.setProvider(provider.name());
            o.setCreatedByUserId(userId);
            o.setProviderOrderId("pending:" + UUID.randomUUID());
            o = orders.saveAndFlush(o);
            o.setProviderOrderId("pending:" + o.getId());
            return orders.save(o);
        });
        UUID id = saved.getId();
        PaymentProvider.ProviderOrder po;
        try {
            po = provider.createOrder(id.toString(), amount, method,
                    Map.of("orgId", orgId.toString(), "purpose", purpose));
        } catch (PaymentProviderRejectedException | PaymentProviderUnavailableException e) {
            log.warn("Provider createOrder failed for payment order {}: {}", id, e.getMessage());
            tx.executeWithoutResult(s -> orders.findById(id).ifPresent(o -> {
                o.setStatus(PaymentOrder.Status.FAILED);
                orders.save(o);
            }));
            throw new PaymentApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_UNAVAILABLE",
                    "The payment provider is unavailable; try again shortly", e);
        }
        PaymentOrder updated = tx.execute(s -> {
            PaymentOrder o = orders.findById(id).orElseThrow();
            o.setProviderOrderId(po.orderId());
            return orders.save(o);
        });
        return new CheckoutResponse(PaymentOrderView.of(updated), provider.name(), po.orderId(), po.checkout());
    }

    /** Orders left CREATED for over 24 hours become EXPIRED; a capture arriving later still credits them. */
    public int expireStaleOrders() {
        Integer expired = tx.execute(s -> orders.expireCreatedBefore(Instant.now().minus(Duration.ofHours(24))));
        if (expired != null && expired > 0) log.info("Expired {} unpaid payment orders", expired);
        return expired == null ? 0 : expired;
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 && amount.stripTrailingZeros().scale() > 2) {
            throw new PaymentApiException(HttpStatus.BAD_REQUEST, "AMOUNT_INVALID",
                    "Amount must be positive with at most 2 decimal places");
        }
    }

    private BigDecimal remainingToday(UUID orgId) {
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        BigDecimal used = orders.topUpTotalSince(orgId, dayStart);
        return props.topupDailyLimit().subtract(used).max(BigDecimal.ZERO).setScale(2);
    }
}
