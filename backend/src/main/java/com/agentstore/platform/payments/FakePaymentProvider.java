package com.agentstore.platform.payments;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory provider for dev and tests. One shared bean per Spring context, so tests that need a clean slate call
 * {@link #reset()}. Every record carries a settable {@code at} so day-based fetches can be seeded.
 */
public class FakePaymentProvider implements PaymentProvider {

    private volatile boolean failNextCreateOrder;

    /** Makes the next {@link #createOrder} throw {@link PaymentProviderUnavailableException}; cleared by use or {@link #reset()}. */
    public void failNextCreateOrder() {
        failNextCreateOrder = true;
    }

    private final java.util.Set<String> failRefundsFor = ConcurrentHashMap.newKeySet();
    private volatile boolean timeoutNextRefund;
    private volatile boolean refundsStayPending;

    /** Makes every {@link #refund} against {@code paymentId} throw {@link PaymentProviderRejectedException}; cleared by {@link #reset()}. */
    public void failRefundsFor(String paymentId) {
        failRefundsFor.add(paymentId);
    }

    /**
     * Makes the next {@link #refund} record the refund and then throw {@link PaymentProviderUnavailableException},
     * as a timeout after the provider acted does; {@link #findRefund} finds it. Cleared by use or {@link #reset()}.
     */
    public void timeoutNextRefund() {
        timeoutNextRefund = true;
    }

    /** New refunds are recorded as PENDING (as a real gateway usually answers) instead of PROCESSED; cleared by {@link #reset()}. */
    public void refundsStayPending(boolean pending) {
        refundsStayPending = pending;
    }

    private final java.util.concurrent.atomic.AtomicInteger refundCalls = new java.util.concurrent.atomic.AtomicInteger();
    private volatile java.util.concurrent.CountDownLatch holdEntered;
    private volatile java.util.concurrent.CountDownLatch holdRelease;
    private volatile boolean unavailableNextRefund;

    /**
     * Makes the next {@link #refund} count down {@code entered} and wait for {@code release} before acting, outside
     * any lock, so a test can race a second submitter against it. Cleared by use or {@link #reset()}.
     */
    public synchronized void holdNextRefund(java.util.concurrent.CountDownLatch entered,
                                            java.util.concurrent.CountDownLatch release) {
        holdEntered = entered;
        holdRelease = release;
    }

    /** Makes the next {@link #refund} throw {@link PaymentProviderUnavailableException} without recording anything. */
    public void unavailableNextRefund() {
        unavailableNextRefund = true;
    }

    /** How many times {@link #refund} was called since the last reset (each call could pay out at a real gateway). */
    public int refundCalls() { return refundCalls.get(); }

    public static final String SIGNATURE_HEADER = "X-Fake-Signature";
    public static final String EVENT_ID_HEADER = "X-Fake-Event-Id";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Getter @Setter
    public static class FakeOrder {
        private String orderId;
        private String receipt;
        private BigDecimal amount;
        private PaymentMethod method;
        private Map<String, String> notes;
        private Instant at;
    }

    @Getter @Setter
    public static class FakePayment {
        private String paymentId;
        private String orderId;
        private BigDecimal amount;
        private String status;
        private String virtualAccountId;
        private Instant at;
    }

    @Getter @Setter
    public static class FakeRefund {
        private String refundId;
        private String paymentId;
        private BigDecimal amount;
        private String status;
        private String receipt;
        private Instant at;
    }

    @Getter @Setter
    public static class FakeSettlement {
        private String settlementId;
        private BigDecimal fees;
        private Instant at;
    }

    @Getter @Setter
    public static class FakeVirtualAccount {
        private UUID orgId;
        private ProviderVirtualAccount account;
        private Instant at;
    }

    private final java.util.concurrent.atomic.AtomicInteger virtualAccountCalls = new java.util.concurrent.atomic.AtomicInteger();
    private final String webhookSecret;
    private final List<FakeOrder> orders = new CopyOnWriteArrayList<>();
    private final List<FakePayment> payments = new CopyOnWriteArrayList<>();
    private final List<FakeRefund> refunds = new CopyOnWriteArrayList<>();
    private final List<FakeSettlement> settlements = new CopyOnWriteArrayList<>();
    private final Map<UUID, FakeVirtualAccount> virtualAccounts = new ConcurrentHashMap<>();

    public FakePaymentProvider(PaymentProperties.Fake config) {
        this.webhookSecret = config == null || config.webhookSecret() == null ? "" : config.webhookSecret();
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public ProviderOrder createOrder(String receipt, BigDecimal amount, PaymentMethod method, Map<String, String> notes) {
        if (failNextCreateOrder) {
            failNextCreateOrder = false;
            throw new PaymentProviderUnavailableException("fake provider: injected createOrder failure");
        }
        FakeOrder o = new FakeOrder();
        o.setOrderId("fake_order_" + UUID.randomUUID().toString().replace("-", ""));
        o.setReceipt(receipt);
        o.setAmount(amount);
        o.setMethod(method);
        o.setNotes(notes == null ? Map.of() : Map.copyOf(notes));
        o.setAt(Instant.now());
        orders.add(o);
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("provider", "fake");
        checkout.put("orderId", o.getOrderId());
        checkout.put("method", method.name());
        checkout.put("amount", amount.toPlainString());
        return new ProviderOrder(o.getOrderId(), checkout);
    }

    @Override
    public ProviderVirtualAccount createVirtualAccount(UUID orgId, String orgName) {
        virtualAccountCalls.incrementAndGet();
        return virtualAccounts.computeIfAbsent(orgId, id -> {
            String suffix = id.toString().replace("-", "");
            FakeVirtualAccount v = new FakeVirtualAccount();
            v.setOrgId(id);
            v.setAccount(new ProviderVirtualAccount("fake_va_" + suffix,
                    "FAKE" + suffix.substring(0, 12).toUpperCase(), "FAKE0000001", orgName));
            v.setAt(Instant.now());
            return v;
        }).getAccount();
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String receipt) {
        refundCalls.incrementAndGet();
        java.util.concurrent.CountDownLatch entered;
        java.util.concurrent.CountDownLatch release;
        synchronized (this) {
            entered = holdEntered;
            release = holdRelease;
            holdEntered = null;
            holdRelease = null;
        }
        if (release != null) {
            entered.countDown();
            try {
                if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new PaymentProviderUnavailableException("fake provider: hold timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PaymentProviderUnavailableException("fake provider: interrupted");
            }
        }
        if (unavailableNextRefund) {
            unavailableNextRefund = false;
            throw new PaymentProviderUnavailableException("fake provider: injected refund outage");
        }
        return recordRefund(providerPaymentId, amount, receipt);
    }

    private synchronized ProviderRefund recordRefund(String providerPaymentId, BigDecimal amount, String receipt) {
        Optional<ProviderRefund> existing = findRefund(providerPaymentId, receipt);
        if (existing.isPresent()) return existing.get();
        if (failRefundsFor.contains(providerPaymentId)) {
            throw new PaymentProviderRejectedException("fake provider: refunds rejected for " + providerPaymentId);
        }
        FakePayment p = payments.stream().filter(x -> x.getPaymentId().equals(providerPaymentId)).findFirst()
                .orElseThrow(() -> new PaymentProviderRejectedException("Unknown payment " + providerPaymentId));
        BigDecimal already = refunds.stream().filter(r -> r.getPaymentId().equals(providerPaymentId))
                .filter(r -> !"FAILED".equals(r.getStatus()))
                .map(FakeRefund::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (already.add(amount).compareTo(p.getAmount()) > 0) {
            throw new PaymentProviderRejectedException("Refund exceeds the payment amount");
        }
        FakeRefund r = new FakeRefund();
        r.setRefundId("fake_rfnd_" + UUID.randomUUID().toString().replace("-", ""));
        r.setPaymentId(providerPaymentId);
        r.setAmount(amount);
        r.setStatus(refundsStayPending ? "PENDING" : "PROCESSED");
        r.setReceipt(receipt);
        r.setAt(Instant.now());
        refunds.add(r);
        if (timeoutNextRefund) {
            timeoutNextRefund = false;
            throw new PaymentProviderUnavailableException("fake provider: injected refund timeout");
        }
        return toProviderRefund(r);
    }

    @Override
    public Optional<ProviderRefund> findRefund(String providerPaymentId, String receipt) {
        return refunds.stream()
                .filter(r -> r.getPaymentId().equals(providerPaymentId) && receipt != null && receipt.equals(r.getReceipt()))
                .findFirst().map(FakePaymentProvider::toProviderRefund);
    }

    @Override
    public boolean verifyWebhook(byte[] body, Map<String, String> headers) {
        String given = header(headers, SIGNATURE_HEADER);
        if (given == null || body == null) return false;
        return MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PaymentWebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        try {
            Map<String, String> m = JSON.readValue(body, new TypeReference<Map<String, String>>() {});
            return new PaymentWebhookEvent(m.get("eventId"),
                    PaymentWebhookEvent.Kind.valueOf(m.get("kind")), m.get("orderId"), m.get("paymentId"),
                    m.get("refundId"), m.get("virtualAccountId"),
                    m.get("amount") == null ? null : new BigDecimal(m.get("amount")),
                    m.get("method") == null ? null : PaymentMethod.valueOf(m.get("method")),
                    m.get("failureReason"));
        } catch (IOException | IllegalArgumentException e) {
            throw new PaymentProviderRejectedException("Malformed fake webhook body", e);
        }
    }

    @Override
    public List<ProviderPayment> fetchPayments(LocalDate dayUtc) {
        return payments.stream().filter(p -> onDay(p.getAt(), dayUtc))
                .map(p -> new ProviderPayment(p.getPaymentId(), p.getOrderId(), p.getAmount(), p.getStatus(),
                        p.getVirtualAccountId())).toList();
    }

    @Override
    public List<ProviderRefund> fetchRefunds(LocalDate dayUtc) {
        return refunds.stream().filter(r -> onDay(r.getAt(), dayUtc)).map(FakePaymentProvider::toProviderRefund).toList();
    }

    @Override
    public List<ProviderSettlement> fetchSettlements(LocalDate dayUtc) {
        return settlements.stream().filter(s -> onDay(s.getAt(), dayUtc))
                .map(s -> new ProviderSettlement(s.getSettlementId(), s.getFees())).toList();
    }

    // ---- dev and test helpers ----

    public byte[] webhookBody(PaymentWebhookEvent e) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("eventId", e.eventId());
        m.put("kind", e.kind().name());
        putIfNotNull(m, "orderId", e.orderId());
        putIfNotNull(m, "paymentId", e.paymentId());
        putIfNotNull(m, "refundId", e.refundId());
        putIfNotNull(m, "virtualAccountId", e.virtualAccountId());
        putIfNotNull(m, "amount", e.amount() == null ? null : e.amount().toPlainString());
        putIfNotNull(m, "method", e.method() == null ? null : e.method().name());
        putIfNotNull(m, "failureReason", e.failureReason());
        try {
            return JSON.writeValueAsBytes(m);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public Map<String, String> signedHeaders(byte[] body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(SIGNATURE_HEADER, sign(body));
        try {
            Map<String, String> m = JSON.readValue(body, new TypeReference<Map<String, String>>() {});
            if (m.get("eventId") != null) headers.put(EVENT_ID_HEADER, m.get("eventId"));
        } catch (IOException ignored) {
            // an unparseable body simply has no event id header
        }
        return headers;
    }

    /** Clears every record; the bean is shared across a Spring context, so tests call this when they need a clean slate. */
    public void reset() {
        failNextCreateOrder = false;
        failRefundsFor.clear();
        timeoutNextRefund = false;
        refundsStayPending = false;
        unavailableNextRefund = false;
        refundCalls.set(0);
        synchronized (this) {
            holdEntered = null;
            holdRelease = null;
        }
        orders.clear();
        payments.clear();
        refunds.clear();
        settlements.clear();
        virtualAccounts.clear();
        virtualAccountCalls.set(0);
    }

    /** How many times {@link #createVirtualAccount} was called since the last reset. */
    public int virtualAccountCalls() { return virtualAccountCalls.get(); }

    public List<FakeOrder> orders() { return List.copyOf(orders); }
    public List<FakePayment> payments() { return List.copyOf(payments); }
    public List<FakeRefund> refunds() { return List.copyOf(refunds); }
    public List<FakeSettlement> settlements() { return List.copyOf(settlements); }

    public FakePayment seedPayment(String paymentId, String orderId, BigDecimal amount, String status,
                                   String virtualAccountId, Instant at) {
        FakePayment p = new FakePayment();
        p.setPaymentId(paymentId);
        p.setOrderId(orderId);
        p.setAmount(amount);
        p.setStatus(status);
        p.setVirtualAccountId(virtualAccountId);
        p.setAt(at);
        payments.add(p);
        return p;
    }

    public FakeRefund seedRefund(String refundId, String paymentId, BigDecimal amount, String status,
                                 String receipt, Instant at) {
        FakeRefund r = new FakeRefund();
        r.setRefundId(refundId);
        r.setPaymentId(paymentId);
        r.setAmount(amount);
        r.setStatus(status);
        r.setReceipt(receipt);
        r.setAt(at);
        refunds.add(r);
        return r;
    }

    public FakeSettlement seedSettlement(String settlementId, BigDecimal fees, Instant at) {
        FakeSettlement s = new FakeSettlement();
        s.setSettlementId(settlementId);
        s.setFees(fees);
        s.setAt(at);
        settlements.add(s);
        return s;
    }

    /** Records a captured payment against an order created here, as if the buyer had paid it. */
    public FakePayment capture(String orderId, String paymentId) {
        FakeOrder o = orders.stream().filter(x -> x.getOrderId().equals(orderId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + orderId));
        return seedPayment(paymentId, orderId, o.getAmount(), "captured", null, Instant.now());
    }

    private static ProviderRefund toProviderRefund(FakeRefund r) {
        return new ProviderRefund(r.getRefundId(), r.getPaymentId(), r.getAmount(), r.getStatus(), r.getReceipt(),
                r.getAt());
    }

    private static boolean onDay(Instant at, LocalDate day) {
        return at != null && at.atZone(ZoneOffset.UTC).toLocalDate().equals(day);
    }

    private static void putIfNotNull(Map<String, String> m, String k, String v) {
        if (v != null) m.put(k, v);
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
