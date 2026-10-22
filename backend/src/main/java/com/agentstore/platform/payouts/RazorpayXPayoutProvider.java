package com.agentstore.platform.payouts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * RazorpayX adapter. Rupees ({@link BigDecimal}) cross the seam; paise ({@code long}) exist only in here.
 *
 * <p>Errors: 5xx, 401, 403, 408, 429, timeouts, I/O failures and unreadable 2xx answers become
 * {@link PayoutProviderUnavailableException}; a 400 whose description mentions "insufficient" becomes
 * {@link PayoutInsufficientBalanceException}; any other 4xx becomes {@link PayoutProviderRejectedException}. Messages carry
 * only the endpoint name, the status and RazorpayX's error {@code code}: never a request or response body (they hold
 * account numbers and names) or a secret. This class does not log.
 */
public class RazorpayXPayoutProvider implements PayoutProvider {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    static final String EVENT_ID_HEADER = "X-Razorpay-Event-Id";
    static final String IDEMPOTENCY_HEADER = "X-Payout-Idempotency";
    static final String DEFAULT_BASE_URL = "https://api.razorpay.com";
    /** The penny-drop amount, in paise, for a bank account validation. */
    private static final long PENNY_DROP_PAISE = 100;
    private static final int PAGE_SIZE = 100;
    /** A guard against a provider that ignores {@code skip}. */
    private static final int MAX_PAGES = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String accountNumber;
    private final byte[] webhookSecret;

    public RazorpayXPayoutProvider(RestClient.Builder builder, PayoutProperties props) {
        this(builder, props, true);
    }

    /** {@code applyTimeouts=false} keeps the builder's request factory, as a {@code MockRestServiceServer} binding needs. */
    RazorpayXPayoutProvider(RestClient.Builder builder, PayoutProperties props, boolean applyTimeouts) {
        PayoutProperties.RazorpayX config = props == null ? null : props.razorpayx();
        List<String> missing = new ArrayList<>();
        if (config == null || isBlank(config.keyId())) missing.add("RAZORPAY_KEY_ID");
        if (config == null || isBlank(config.keySecret())) missing.add("RAZORPAY_KEY_SECRET");
        if (config == null || isBlank(config.accountNumber())) missing.add("RAZORPAYX_ACCOUNT_NUMBER");
        if (config == null || isBlank(config.webhookSecret())) missing.add("RAZORPAYX_WEBHOOK_SECRET");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Payout provider razorpayx requires these environment variables to be set: " + String.join(", ", missing));
        }
        this.accountNumber = config.accountNumber();
        this.webhookSecret = config.webhookSecret().getBytes(StandardCharsets.UTF_8);
        String baseUrl = isBlank(config.baseUrl()) ? DEFAULT_BASE_URL : config.baseUrl();
        if (applyTimeouts) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(CONNECT_TIMEOUT);
            factory.setReadTimeout(READ_TIMEOUT);
            builder = builder.requestFactory(factory);
        }
        String secret = config.keySecret();
        this.http = builder.baseUrl(baseUrl)
                .defaultHeaders(h -> h.setBasicAuth(config.keyId(), secret))
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public String name() {
        return "razorpayx";
    }

    // ---- contacts, fund accounts, validations ----

    @Override
    public String createContact(UUID orgId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("type", "vendor");
        body.put("reference_id", String.valueOf(orgId));
        JsonNode contact = call("create contact", () -> http.post().uri("/v1/contacts")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        return requiredText(contact, "id", "create contact");
    }

    @Override
    public String createFundAccount(String contactId, FundAccountDetails details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contact_id", contactId);
        if (details.type() == PayoutAccountType.BANK) {
            body.put("account_type", "bank_account");
            Map<String, Object> bank = new LinkedHashMap<>();
            bank.put("name", details.holderName());
            bank.put("ifsc", details.ifsc());
            bank.put("account_number", details.accountNumber());
            body.put("bank_account", bank);
        } else {
            body.put("account_type", "vpa");
            body.put("vpa", Map.of("address", details.vpa()));
        }
        JsonNode fa = call("create fund account", () -> http.post().uri("/v1/fund_accounts")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        return requiredText(fa, "id", "create fund account");
    }

    @Override
    public ProviderValidation validateFundAccount(String fundAccountId, PayoutAccountType type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_number", accountNumber);
        body.put("fund_account", Map.of("id", fundAccountId));
        if (type == PayoutAccountType.BANK) {
            body.put("amount", PENNY_DROP_PAISE);
            body.put("currency", "INR");
        }
        JsonNode v = call("validate fund account", () -> http.post().uri("/v1/fund_accounts/validations")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        requiredText(v, "id", "validate fund account");
        return toValidation(v);
    }

    @Override
    public Optional<ProviderValidation> fetchValidation(String validationId) {
        JsonNode v = callOrMissing("fetch validation", () -> http.get()
                .uri("/v1/fund_accounts/validations/{id}", validationId).retrieve().body(String.class));
        if (v == null) return Optional.empty();
        requiredText(v, "id", "fetch validation");
        return Optional.of(toValidation(v));
    }

    private static ProviderValidation toValidation(JsonNode v) {
        return toValidation(v, false);
    }

    /**
     * {@code completedEvent}: the entity came in a {@code fund_account.validation.completed} webhook, so a missing
     * {@code status} is read as completed and the outcome comes from {@code results.account_status}.
     */
    private static ProviderValidation toValidation(JsonNode v, boolean completedEvent) {
        String status = text(v, "status");
        if (status == null && completedEvent) status = "completed";
        JsonNode results = v.path("results");
        String accountStatus = text(results, "account_status");
        String mapped;
        if ("completed".equalsIgnoreCase(status)) {
            mapped = "active".equalsIgnoreCase(accountStatus) ? "COMPLETED" : "FAILED";
        } else if ("failed".equalsIgnoreCase(status)) {
            mapped = "FAILED";
        } else {
            mapped = "PENDING";
        }
        String name = "COMPLETED".equals(mapped) ? text(results, "registered_name") : null;
        String failure = "FAILED".equals(mapped) ? validationFailure(v) : null;
        return new ProviderValidation(text(v, "id"), mapped, name, failure);
    }

    private static String validationFailure(JsonNode v) {
        String reason = text(v, "failure_reason");
        if (reason == null) reason = text(v.path("results"), "account_status");
        return reason != null ? reason : "Validation failed";
    }

    // ---- payouts ----

    @Override
    public ProviderPayout createPayout(String fundAccountId, BigDecimal amount, PayoutMode mode, String idempotencyKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_number", accountNumber);
        body.put("fund_account_id", fundAccountId);
        body.put("amount", toPaise(amount));
        body.put("currency", "INR");
        body.put("mode", mode.name());
        body.put("purpose", "payout");
        body.put("queue_if_low_balance", false);
        body.put("reference_id", idempotencyKey);
        JsonNode payout = call("create payout", () -> http.post().uri("/v1/payouts")
                .headers(h -> h.set(IDEMPOTENCY_HEADER, idempotencyKey))
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        requiredText(payout, "id", "create payout");
        return toPayout(payout, "create payout");
    }

    @Override
    public Optional<ProviderPayout> fetchPayout(String payoutId) {
        JsonNode payout = callOrMissing("fetch payout", () -> http.get().uri("/v1/payouts/{id}", payoutId)
                .retrieve().body(String.class));
        if (payout == null) return Optional.empty();
        requiredText(payout, "id", "fetch payout");
        return Optional.of(toPayout(payout, "fetch payout"));
    }

    @Override
    public List<ProviderPayout> fetchPayouts(LocalDate dayUtc) {
        long from = dayUtc.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long to = dayUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1;
        List<ProviderPayout> out = new ArrayList<>();
        for (int page = 0, skip = 0; page < MAX_PAGES; page++, skip += PAGE_SIZE) {
            int s = skip;
            JsonNode list = call("list payouts", () -> http.get().uri(b -> b.path("/v1/payouts")
                    .queryParam("account_number", accountNumber).queryParam("from", from).queryParam("to", to)
                    .queryParam("count", PAGE_SIZE).queryParam("skip", s).build())
                    .retrieve().body(String.class));
            JsonNode items = list.get("items");
            if (items == null || !items.isArray()) {
                throw new PayoutProviderUnavailableException("RazorpayX list payouts answer has no items array");
            }
            for (JsonNode item : items) out.add(toPayout(item, "list payouts"));
            if (items.size() < PAGE_SIZE) return out;
        }
        throw new PayoutProviderUnavailableException("RazorpayX list payouts returned too many pages");
    }

    private static ProviderPayout toPayout(JsonNode p, String endpoint) {
        try {
            return new ProviderPayout(text(p, "id"), text(p, "reference_id"), rupees(p, "amount"), fees(p),
                    payoutStatus(text(p, "status")), text(p, "utr"), failureReason(p), instant(p.path("created_at")));
        } catch (ArithmeticException e) {
            throw new PayoutProviderUnavailableException("RazorpayX " + endpoint + " returned a fractional paise amount");
        }
    }

    /** Our status words for RazorpayX's lowercase ones; an unknown word reads as still in flight. */
    private static String payoutStatus(String status) {
        if (status == null) return "PROCESSING";
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "queued", "pending", "scheduled" -> "QUEUED";
            case "processed" -> "PROCESSED";
            case "reversed" -> "REVERSED";
            case "failed", "rejected", "cancelled", "canceled" -> "FAILED";
            default -> "PROCESSING";
        };
    }

    /**
     * The fee we absorb, in rupees. RazorpayX's {@code fees} already includes {@code tax} (fees 590 = 500 + tax 90),
     * so it is used alone; {@code tax} is only a fallback when {@code fees} is absent. Null when RazorpayX sent neither.
     */
    private static BigDecimal fees(JsonNode p) {
        BigDecimal fees = rupees(p, "fees");
        return fees != null ? fees : rupees(p, "tax");
    }

    private static String failureReason(JsonNode p) {
        String reason = text(p, "failure_reason");
        if (reason == null) reason = text(p.path("status_details"), "description");
        return reason;
    }

    private static Instant instant(JsonNode created) {
        return created.isIntegralNumber() && created.canConvertToLong() ? Instant.ofEpochSecond(created.longValue()) : null;
    }

    // ---- webhooks ----

    @Override
    public boolean verifyWebhook(byte[] body, Map<String, String> headers) {
        String given = header(headers, SIGNATURE_HEADER);
        if (given == null || given.isBlank() || body == null) return false;
        byte[] expected = hmacHex(body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    // Duplicated from RazorpayPaymentProvider (private there) rather than widening WS10b for three lines.
    private String hmacHex(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    @Override
    public PayoutWebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        JsonNode root;
        try {
            root = body == null ? null : JSON.readTree(body);
        } catch (IOException e) {
            throw new PayoutProviderRejectedException("Malformed RazorpayX webhook body");
        }
        if (root == null || !root.isObject()) {
            throw new PayoutProviderRejectedException("Malformed RazorpayX webhook body");
        }
        String eventId = header(headers, EVENT_ID_HEADER);
        String type = text(root, "event");
        PayoutWebhookEvent.Kind kind = type == null ? PayoutWebhookEvent.Kind.OTHER : switch (type) {
            case "payout.processed" -> PayoutWebhookEvent.Kind.PAYOUT_PROCESSED;
            case "payout.reversed" -> PayoutWebhookEvent.Kind.PAYOUT_REVERSED;
            case "payout.failed", "payout.rejected" -> PayoutWebhookEvent.Kind.PAYOUT_FAILED;
            case "fund_account.validation.completed" -> PayoutWebhookEvent.Kind.VALIDATION_COMPLETED;
            case "fund_account.validation.failed" -> PayoutWebhookEvent.Kind.VALIDATION_FAILED;
            default -> PayoutWebhookEvent.Kind.OTHER;
        };
        JsonNode payload = root.path("payload");
        try {
            return switch (kind) {
                case PAYOUT_PROCESSED, PAYOUT_REVERSED, PAYOUT_FAILED -> {
                    JsonNode payout = payload.path("payout").path("entity");
                    yield new PayoutWebhookEvent(eventId, kind, text(payout, "id"), null, rupees(payout, "amount"),
                            fees(payout), text(payout, "utr"), null, kind == PayoutWebhookEvent.Kind.PAYOUT_FAILED
                            ? orElse(failureReason(payout), "Payout failed") : null, text(payout, "reference_id"));
                }
                case VALIDATION_COMPLETED, VALIDATION_FAILED -> {
                    JsonNode v = validationEntity(payload);
                    ProviderValidation mapped = toValidation(v, kind == PayoutWebhookEvent.Kind.VALIDATION_COMPLETED);
                    boolean ok = kind == PayoutWebhookEvent.Kind.VALIDATION_COMPLETED && "COMPLETED".equals(mapped.status());
                    yield new PayoutWebhookEvent(eventId, ok ? PayoutWebhookEvent.Kind.VALIDATION_COMPLETED
                            : PayoutWebhookEvent.Kind.VALIDATION_FAILED, null, text(v, "id"), null, null, null,
                            ok ? mapped.registeredName() : null, ok ? null : orElse(mapped.failureReason(), "Validation failed"), null);
                }
                case OTHER -> new PayoutWebhookEvent(eventId, kind, null, null, null, null, null, null, null, null);
            };
        } catch (ArithmeticException e) {
            throw new PayoutProviderRejectedException("RazorpayX webhook amount is not a whole number of paise");
        }
    }

    /**
     * The validation entity. RazorpayX documents it under {@code payload["fund_account.validation"]}; this also accepts the
     * nested {@code payload.fund_account.validation} and {@code payload.fund_account_validation} spellings.
     */
    private static JsonNode validationEntity(JsonNode payload) {
        for (JsonNode candidate : new JsonNode[] {payload.path("fund_account.validation"),
                payload.path("fund_account").path("validation"), payload.path("fund_account_validation")}) {
            JsonNode entity = candidate.path("entity");
            if (entity.isObject()) return entity;
        }
        return payload.path("fund_account.validation").path("entity");
    }

    private static String orElse(String v, String fallback) {
        return v != null ? v : fallback;
    }

    // ---- helpers ----

    static long toPaise(BigDecimal rupees) {
        try {
            return rupees.movePointRight(2).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount must be a whole number of paise");
        }
    }

    static BigDecimal toRupees(long paise) {
        return BigDecimal.valueOf(paise).movePointLeft(2);
    }

    private static BigDecimal rupees(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (!v.canConvertToExactIntegral() || !v.canConvertToLong()) throw new ArithmeticException("not whole paise");
        return toRupees(v.longValue());
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    private static String requiredText(JsonNode node, String field, String endpoint) {
        String v = text(node, field);
        if (v == null || v.isBlank()) {
            throw new PayoutProviderUnavailableException("RazorpayX " + endpoint + " answer has no " + field);
        }
        return v;
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static JsonNode call(String endpoint, Supplier<String> request) {
        return run(endpoint, request, false);
    }

    /** As {@link #call} but a 404 answers null: the thing is not there. */
    private static JsonNode callOrMissing(String endpoint, Supplier<String> request) {
        return run(endpoint, request, true);
    }

    private static JsonNode run(String endpoint, Supplier<String> request, boolean missingOn404) {
        String body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (missingOn404 && status == 404) return null;
            JsonNode error = errorNode(e.getResponseBodyAsByteArray());
            String code = errorCode(error);
            String message = "RazorpayX " + endpoint + " answered " + status + (code == null ? "" : " " + code);
            if (status == 400 && description(error).toLowerCase(Locale.ROOT).contains("insufficient")) {
                throw new PayoutInsufficientBalanceException(message);
            }
            // 401/403 mean our keys are wrong or revoked, not that the request was: retry once they are fixed.
            if (status >= 400 && status < 500 && status != 401 && status != 403 && status != 408 && status != 429) {
                throw new PayoutProviderRejectedException(message);
            }
            throw new PayoutProviderUnavailableException(message);
        } catch (RestClientException e) {
            throw new PayoutProviderUnavailableException(
                    "RazorpayX " + endpoint + " unavailable (" + e.getClass().getSimpleName() + ")");
        }
        try {
            JsonNode node = body == null ? null : JSON.readTree(body);
            if (node == null || !node.isObject()) throw new IOException("not an object");
            return node;
        } catch (IOException e) {
            throw new PayoutProviderUnavailableException("RazorpayX " + endpoint + " returned an unreadable answer");
        }
    }

    private static JsonNode errorNode(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            return JSON.readTree(body).path("error");
        } catch (IOException e) {
            return null;
        }
    }

    /** Razorpay's {@code error.code} (e.g. BAD_REQUEST_ERROR), kept only when it looks like a code. */
    private static String errorCode(JsonNode error) {
        String code = text(error, "code");
        return code != null && code.matches("[A-Z0-9_]{1,64}") ? code : null;
    }

    /** Read only to classify the error; never copied into a message. */
    private static String description(JsonNode error) {
        String d = text(error, "description");
        return d == null ? "" : d;
    }
}
