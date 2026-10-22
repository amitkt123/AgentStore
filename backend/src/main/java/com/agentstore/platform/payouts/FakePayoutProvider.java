package com.agentstore.platform.payouts;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

/**
 * In-memory provider for dev and tests. One shared bean per Spring context. Deterministic rules: the registered
 * name is the holder name unless the account number ends 0000 ("SOMEONE ELSE"); a VPA starting "fail" fails
 * validation; an account number ending 1111 leaves validation PENDING until {@link #fetchValidation}; a payout of
 * exactly 999.99 is refused for balance and 777.77 is unavailable (until {@link #clearFailures}).
 */
public class FakePayoutProvider implements PayoutProvider {

    static final String SIGNATURE_HEADER = "X-Fake-Signature";
    private static final ObjectMapper JSON = new ObjectMapper();

    private record FundAccount(String id, FundAccountDetails details) {}

    private final Map<String, FundAccount> fundAccounts = new ConcurrentHashMap<>();
    private final Map<String, ProviderValidation> validations = new ConcurrentHashMap<>();
    private final Map<String, ProviderPayout> payoutsById = new ConcurrentHashMap<>();
    private final Map<String, ProviderPayout> payoutsByKey = new ConcurrentHashMap<>();
    private final String webhookSecret;
    private volatile boolean unavailableCleared;

    public FakePayoutProvider(PayoutProperties.Fake fake) {
        if (fake == null || fake.webhookSecret() == null || fake.webhookSecret().isBlank()) {
            throw new IllegalStateException("Payout provider fake requires agentstore.money.payouts.fake.webhook-secret");
        }
        this.webhookSecret = fake.webhookSecret();
    }

    /** A signed webhook delivery: the raw body and the headers that go with it. */
    public record SignedEvent(byte[] body, Map<String, String> headers) {}

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public String createContact(UUID orgId, String name) {
        return "fake_cont_" + UUID.randomUUID();
    }

    @Override
    public String createFundAccount(String contactId, FundAccountDetails details) {
        String id = "fake_fa_" + UUID.randomUUID();
        fundAccounts.put(id, new FundAccount(id, details));
        return id;
    }

    @Override
    public ProviderValidation validateFundAccount(String fundAccountId, PayoutAccountType type) {
        FundAccount fa = fundAccounts.get(fundAccountId);
        if (fa == null) throw new PayoutProviderRejectedException("Unknown fund account " + fundAccountId);
        String id = "fake_fav_" + UUID.randomUUID();
        FundAccountDetails d = fa.details();
        ProviderValidation v;
        if (d.vpa() != null && d.vpa().startsWith("fail")) {
            v = new ProviderValidation(id, "FAILED", null, "VPA could not be verified");
        } else if (d.accountNumber() != null && d.accountNumber().endsWith("1111")) {
            // pending now; the completed answer is what fetchValidation returns
            validations.put(id, new ProviderValidation(id, "COMPLETED", d.holderName(), null));
            return new ProviderValidation(id, "PENDING", null, null);
        } else {
            String name = d.accountNumber() != null && d.accountNumber().endsWith("0000") ? "SOMEONE ELSE" : d.holderName();
            v = new ProviderValidation(id, "COMPLETED", name, null);
        }
        validations.put(id, v);
        return v;
    }

    @Override
    public Optional<ProviderValidation> fetchValidation(String validationId) {
        return Optional.ofNullable(validations.get(validationId));
    }

    @Override
    public synchronized ProviderPayout createPayout(String fundAccountId, BigDecimal amount, PayoutMode mode,
                                                    String idempotencyKey) {
        ProviderPayout existing = payoutsByKey.get(idempotencyKey);
        if (existing != null) return existing;
        if (amount.compareTo(new BigDecimal("999.99")) == 0) {
            throw new PayoutInsufficientBalanceException("Fake provider balance is too low");
        }
        if (!unavailableCleared && amount.compareTo(new BigDecimal("777.77")) == 0) {
            throw new PayoutProviderUnavailableException("Fake provider is unavailable");
        }
        if (!fundAccounts.containsKey(fundAccountId)) {
            throw new PayoutProviderRejectedException("Unknown fund account " + fundAccountId);
        }
        ProviderPayout p = new ProviderPayout("fake_pout_" + UUID.randomUUID(), idempotencyKey, amount,
                new BigDecimal("0.00"), "PROCESSING", null, null, Instant.now());
        payoutsById.put(p.payoutId(), p);
        payoutsByKey.put(idempotencyKey, p);
        return p;
    }

    @Override
    public Optional<ProviderPayout> fetchPayout(String payoutId) {
        return Optional.ofNullable(payoutsById.get(payoutId));
    }

    @Override
    public List<ProviderPayout> fetchPayouts(LocalDate dayUtc) {
        return payoutsById.values().stream()
                .filter(p -> p.createdAt().atZone(ZoneOffset.UTC).toLocalDate().equals(dayUtc))
                .toList();
    }

    @Override
    public boolean verifyWebhook(byte[] body, Map<String, String> headers) {
        String given = header(headers, SIGNATURE_HEADER);
        if (given == null || body == null) return false;
        return MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PayoutWebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        try {
            Map<String, Object> m = JSON.readValue(body, new TypeReference<Map<String, Object>>() {});
            return new PayoutWebhookEvent(str(m, "eventId"), PayoutWebhookEvent.Kind.valueOf(str(m, "kind")),
                    str(m, "payoutId"), str(m, "validationId"), dec(m, "amount"), dec(m, "fees"), str(m, "utr"),
                    str(m, "registeredName"), str(m, "failureReason"));
        } catch (IOException | RuntimeException e) {
            throw new PayoutProviderRejectedException("Unparseable fake webhook body", e);
        }
    }

    /**
     * Builds a signed event for tests and the dev simulate endpoint. {@code fields} may carry amount, fees, utr,
     * registeredName and failureReason; {@code id} is the payout id (PAYOUT_*) or validation id (VALIDATION_*).
     */
    public SignedEvent signedEvent(PayoutWebhookEvent.Kind kind, String id, Map<String, Object> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventId", "fake_evt_" + UUID.randomUUID());
        m.put("kind", kind.name());
        boolean validation = kind == PayoutWebhookEvent.Kind.VALIDATION_COMPLETED
                || kind == PayoutWebhookEvent.Kind.VALIDATION_FAILED;
        m.put(validation ? "validationId" : "payoutId", id);
        if (fields != null) m.putAll(fields);
        try {
            byte[] body = JSON.writeValueAsBytes(m);
            return new SignedEvent(body, Map.of(SIGNATURE_HEADER, sign(body)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Makes 777.77 succeed from now on, as if the provider came back after a timeout. 999.99 is still refused for
     * balance: that rule stands for the platform's own provider balance, which a retry does not fix.
     */
    public void clearFailures() {
        unavailableCleared = true;
    }

    /** Forgets everything and restores the failure rules; tests that need a clean slate call this. */
    public void reset() {
        unavailableCleared = false;
        fundAccounts.clear();
        validations.clear();
        payoutsById.clear();
        payoutsByKey.clear();
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static BigDecimal dec(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? null : new BigDecimal(v.toString());
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
