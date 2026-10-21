package com.agentstore.platform.payments;

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
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Razorpay adapter. Rupees ({@link BigDecimal}) cross the seam; paise ({@code long}) exist only in here.
 *
 * <p>Errors: a definite 4xx answer becomes {@link PaymentProviderRejectedException}; a 5xx, 408, 429, timeout, I/O
 * failure or unreadable 2xx answer becomes {@link PaymentProviderUnavailableException}. Messages carry only the
 * endpoint name, the status code and Razorpay's error {@code code}, never a request or response body or a secret.
 */
public class RazorpayPaymentProvider implements PaymentProvider {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    static final String SIGNATURE_HEADER = "X-Razorpay-Signature";
    static final String EVENT_ID_HEADER = "X-Razorpay-Event-Id";
    /** Razorpay answers a repeated refund request carrying the same key with the first refund instead of a new one. */
    static final String REFUND_IDEMPOTENCY_HEADER = "X-Refund-Idempotency";
    static final String DEFAULT_BASE_URL = "https://api.razorpay.com";
    static final String CHECKOUT_NAME = "Karya";

    /** Razorpay's largest page size for list endpoints. */
    private static final int PAGE_SIZE = 100;
    /** A guard against a provider that ignores {@code skip}: 100 000 records a day is far beyond expected volume. */
    private static final int MAX_PAGES = 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final String keyId;
    private final byte[] webhookSecret;

    public RazorpayPaymentProvider(RestClient.Builder builder, PaymentProperties props) {
        this(builder, props, true);
    }

    /** {@code applyTimeouts=false} keeps the builder's request factory, as a {@code MockRestServiceServer} binding needs. */
    RazorpayPaymentProvider(RestClient.Builder builder, PaymentProperties props, boolean applyTimeouts) {
        PaymentProperties.Razorpay config = props == null ? null : props.razorpay();
        List<String> missing = new ArrayList<>();
        if (config == null || isBlank(config.keyId())) missing.add("RAZORPAY_KEY_ID");
        if (config == null || isBlank(config.keySecret())) missing.add("RAZORPAY_KEY_SECRET");
        if (config == null || isBlank(config.webhookSecret())) missing.add("RAZORPAY_WEBHOOK_SECRET");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Payment provider razorpay requires these environment variables to be set: " + String.join(", ", missing));
        }
        this.keyId = config.keyId();
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
        return "razorpay";
    }

    // ---- orders, virtual accounts, refunds ----

    @Override
    public ProviderOrder createOrder(String receipt, BigDecimal amount, PaymentMethod method, Map<String, String> notes) {
        String block = checkoutMethod(method);
        long paise = toPaise(amount);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", paise);
        body.put("currency", "INR");
        body.put("receipt", receipt);
        body.put("notes", notes == null ? Map.of() : notes);
        body.put("payment_capture", 1);   // auto-capture: an authorized-only payment never credits a wallet
        JsonNode order = call("create order", () -> http.post().uri("/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        String orderId = requiredText(order, "id", "create order");

        Map<String, Object> blockSpec = new LinkedHashMap<>();
        blockSpec.put("name", blockName(method));
        blockSpec.put("instruments", List.of(Map.of("method", block)));
        Map<String, Object> display = new LinkedHashMap<>();
        display.put("blocks", Map.of(block, blockSpec));
        display.put("sequence", List.of("block." + block));
        display.put("preferences", Map.of("show_default_blocks", false));

        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("key", keyId);
        checkout.put("order_id", orderId);
        checkout.put("amount", paise);
        checkout.put("currency", "INR");
        checkout.put("name", CHECKOUT_NAME);
        checkout.put("config", Map.of("display", display));
        return new ProviderOrder(orderId, checkout);
    }

    private static String checkoutMethod(PaymentMethod method) {
        if (method == null) throw new IllegalArgumentException("A payment method is required");
        return switch (method) {
            case UPI -> "upi";
            case CARD -> "card";
            case NETBANKING -> "netbanking";
            case BANK_TRANSFER -> throw new IllegalArgumentException("Bank transfers use a virtual account, not an order");
        };
    }

    private static String blockName(PaymentMethod method) {
        return switch (method) {
            case UPI -> "Pay using UPI";
            case CARD -> "Pay using card";
            case NETBANKING -> "Pay using netbanking";
            case BANK_TRANSFER -> throw new IllegalArgumentException("Bank transfers use a virtual account, not an order");
        };
    }

    @Override
    public ProviderVirtualAccount createVirtualAccount(UUID orgId, String orgName) {
        String name = orgName == null ? "" : orgName.strip();
        if (name.length() > 100) name = name.substring(0, 100);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receivers", Map.of("types", List.of("bank_account")));
        body.put("description", ("Karya wallet top-ups " + name).strip());
        body.put("notes", Map.of("org_id", String.valueOf(orgId)));
        JsonNode va = call("create virtual account", () -> http.post().uri("/v1/virtual_accounts")
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        String id = requiredText(va, "id", "create virtual account");
        for (JsonNode receiver : va.path("receivers")) {
            if ("bank_account".equals(text(receiver, "entity"))) {
                return new ProviderVirtualAccount(id, requiredText(receiver, "account_number", "create virtual account"),
                        requiredText(receiver, "ifsc", "create virtual account"), text(receiver, "name"));
            }
        }
        throw new PaymentProviderUnavailableException("Razorpay create virtual account returned no bank account receiver");
    }

    @Override
    public ProviderRefund refund(String providerPaymentId, BigDecimal amount, String receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toPaise(amount));
        body.put("receipt", receipt);
        JsonNode refund = call("refund", () -> http.post().uri("/v1/payments/{id}/refund", providerPaymentId)
                .headers(h -> {
                    // The receipt is the refund part id, a UUID: within the header's 10+ chars of [A-Za-z0-9_-].
                    if (receipt != null && receipt.matches("[A-Za-z0-9_-]{10,}")) h.set(REFUND_IDEMPOTENCY_HEADER, receipt);
                })
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class));
        requiredText(refund, "id", "refund");
        return toRefund(refund);
    }

    @Override
    public Optional<ProviderRefund> findRefund(String providerPaymentId, String receipt) {
        if (receipt == null) return Optional.empty();
        for (int page = 0, skip = 0; page < MAX_PAGES; page++, skip += PAGE_SIZE) {
            int s = skip;
            JsonNode list = call("list payment refunds", () -> http.get().uri(b -> b.path("/v1/payments/{id}/refunds")
                    .queryParam("count", PAGE_SIZE).queryParam("skip", s).build(providerPaymentId))
                    .retrieve().body(String.class));
            JsonNode items = items(list, "list payment refunds");
            for (JsonNode item : items) {
                if (receipt.equals(text(item, "receipt"))) return Optional.of(toRefund(item));
            }
            if (items.size() < PAGE_SIZE) return Optional.empty();
        }
        throw new PaymentProviderUnavailableException("Razorpay list payment refunds returned too many pages");
    }

    // ---- webhooks ----

    @Override
    public boolean verifyWebhook(byte[] body, Map<String, String> headers) {
        String given = header(headers, SIGNATURE_HEADER);
        if (given == null || given.isBlank() || body == null) return false;
        byte[] expected = hmacHex(body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, given.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

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
    public PaymentWebhookEvent parseWebhook(byte[] body, Map<String, String> headers) {
        JsonNode root;
        try {
            root = body == null ? null : JSON.readTree(body);
        } catch (IOException e) {
            throw new PaymentProviderRejectedException("Malformed Razorpay webhook body");
        }
        if (root == null || !root.isObject()) {
            throw new PaymentProviderRejectedException("Malformed Razorpay webhook body");
        }
        String eventId = header(headers, EVENT_ID_HEADER);
        String type = text(root, "event");
        PaymentWebhookEvent.Kind kind = kindOf(type);
        JsonNode payload = root.path("payload");
        JsonNode payment = payload.path("payment").path("entity");
        try {
            return switch (kind) {
                case REFUND_PROCESSED, REFUND_FAILED -> {
                    JsonNode refund = payload.path("refund").path("entity");
                    yield new PaymentWebhookEvent(eventId, kind, null, text(refund, "payment_id"), text(refund, "id"),
                            null, rupees(refund, "amount"), null, null);
                }
                case VIRTUAL_ACCOUNT_CREDITED -> {
                    String vaId = text(payload.path("virtual_account").path("entity"), "id");
                    if (vaId == null) vaId = text(payload.path("bank_transfer").path("entity"), "virtual_account_id");
                    yield new PaymentWebhookEvent(eventId, kind, text(payment, "order_id"), text(payment, "id"), null,
                            vaId, rupees(payment, "amount"), methodOf(text(payment, "method")), null);
                }
                default -> {
                    String orderId = text(payment, "order_id");
                    if (orderId == null) orderId = text(payload.path("order").path("entity"), "id");
                    String failure = kind == PaymentWebhookEvent.Kind.PAYMENT_FAILED
                            ? text(payment, "error_description") : null;
                    yield new PaymentWebhookEvent(eventId, kind, orderId, text(payment, "id"), null, null,
                            rupees(payment, "amount"), methodOf(text(payment, "method")), failure);
                }
            };
        } catch (ArithmeticException e) {
            throw new PaymentProviderRejectedException("Razorpay webhook amount is not a whole number of paise");
        }
    }

    private static PaymentWebhookEvent.Kind kindOf(String type) {
        if (type == null) return PaymentWebhookEvent.Kind.OTHER;
        return switch (type) {
            case "payment.captured" -> PaymentWebhookEvent.Kind.PAYMENT_CAPTURED;
            case "payment.failed" -> PaymentWebhookEvent.Kind.PAYMENT_FAILED;
            case "order.paid" -> PaymentWebhookEvent.Kind.ORDER_PAID;
            case "virtual_account.credited" -> PaymentWebhookEvent.Kind.VIRTUAL_ACCOUNT_CREDITED;
            case "refund.processed" -> PaymentWebhookEvent.Kind.REFUND_PROCESSED;
            case "refund.failed" -> PaymentWebhookEvent.Kind.REFUND_FAILED;
            default -> PaymentWebhookEvent.Kind.OTHER;
        };
    }

    private static PaymentMethod methodOf(String method) {
        if (method == null) return null;
        return switch (method) {
            case "upi" -> PaymentMethod.UPI;
            case "card" -> PaymentMethod.CARD;
            case "netbanking" -> PaymentMethod.NETBANKING;
            case "bank_transfer" -> PaymentMethod.BANK_TRANSFER;
            default -> null;
        };
    }

    // ---- day fetches for reconciliation ----

    @Override
    public Optional<ProviderPayment> fetchPayment(String paymentId) {
        JsonNode p;
        try {
            p = call("fetch payment", () -> http.get().uri("/v1/payments/{id}", paymentId).retrieve().body(String.class));
        } catch (PaymentProviderRejectedException e) {
            // Razorpay answers an unknown id with a 400 (or 404); either way there is nothing to credit.
            return Optional.empty();
        }
        requiredText(p, "id", "fetch payment");
        try {
            return Optional.of(toPayment(p));
        } catch (ArithmeticException e) {
            throw new PaymentProviderUnavailableException("Razorpay fetch payment returned a fractional paise amount");
        }
    }

    @Override
    public List<ProviderPayment> fetchPayments(LocalDate dayUtc) {
        return fetchDay("list payments", "/v1/payments", dayUtc, RazorpayPaymentProvider::toPayment);
    }

    private static ProviderPayment toPayment(JsonNode p) {
        return new ProviderPayment(text(p, "id"), text(p, "order_id"), rupees(p, "amount"), text(p, "status"),
                text(p, "virtual_account_id"));
    }

    @Override
    public List<ProviderRefund> fetchRefunds(LocalDate dayUtc) {
        return fetchDay("list refunds", "/v1/refunds", dayUtc, RazorpayPaymentProvider::toRefund);
    }

    @Override
    public List<ProviderSettlement> fetchSettlements(LocalDate dayUtc) {
        return fetchDay("list settlements", "/v1/settlements", dayUtc,
                s -> new ProviderSettlement(text(s, "id"), rupees(s, "fees")));
    }

    /** Pages {@code count=100&skip=} through one UTC day, {@code from} and {@code to} being inclusive epoch seconds. */
    private <T> List<T> fetchDay(String endpoint, String path, LocalDate dayUtc, Function<JsonNode, T> mapper) {
        long from = dayUtc.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long to = dayUtc.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1;
        List<T> out = new ArrayList<>();
        for (int page = 0, skip = 0; page < MAX_PAGES; page++, skip += PAGE_SIZE) {
            int s = skip;
            JsonNode list = call(endpoint, () -> http.get().uri(b -> b.path(path).queryParam("from", from)
                    .queryParam("to", to).queryParam("count", PAGE_SIZE).queryParam("skip", s).build())
                    .retrieve().body(String.class));
            JsonNode items = items(list, endpoint);
            try {
                for (JsonNode item : items) out.add(mapper.apply(item));
            } catch (ArithmeticException e) {
                throw new PaymentProviderUnavailableException("Razorpay " + endpoint + " returned a fractional paise amount");
            }
            if (items.size() < PAGE_SIZE) return out;
        }
        throw new PaymentProviderUnavailableException("Razorpay " + endpoint + " returned too many pages");
    }

    // ---- helpers ----

    /**
     * The {@code items} array of a list answer. A 2xx without one is unreadable, not empty: read as empty it would make
     * a refund look absent (and be made again) or a day look quiet.
     */
    private static JsonNode items(JsonNode list, String endpoint) {
        JsonNode items = list.get("items");
        if (items == null || !items.isArray()) {
            throw new PaymentProviderUnavailableException("Razorpay " + endpoint + " answer has no items array");
        }
        return items;
    }

    private static ProviderRefund toRefund(JsonNode r) {
        String status = text(r, "status");
        JsonNode created = r.path("created_at");
        return new ProviderRefund(text(r, "id"), text(r, "payment_id"), rupees(r, "amount"),
                status == null ? null : status.toUpperCase(Locale.ROOT), text(r, "receipt"),
                created.canConvertToLong() && created.isIntegralNumber() ? Instant.ofEpochSecond(created.longValue()) : null);
    }

    static long toPaise(BigDecimal rupees) {
        return rupees.movePointRight(2).longValueExact();
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
            throw new PaymentProviderUnavailableException("Razorpay " + endpoint + " answer has no " + field);
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

    /**
     * Runs one request and parses its body. Only the endpoint name, the status and Razorpay's error code reach the
     * exception message: Spring's own messages can quote the request and response.
     */
    private static JsonNode call(String endpoint, Supplier<String> request) {
        String body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = errorCode(e.getResponseBodyAsByteArray());
            String message = "Razorpay " + endpoint + " answered " + status + (code == null ? "" : " " + code);
            if (status >= 400 && status < 500 && status != 408 && status != 429) {
                throw new PaymentProviderRejectedException(message);
            }
            throw new PaymentProviderUnavailableException(message);
        } catch (RestClientException e) {
            throw new PaymentProviderUnavailableException(
                    "Razorpay " + endpoint + " unavailable (" + e.getClass().getSimpleName() + ")");
        }
        try {
            JsonNode node = body == null ? null : JSON.readTree(body);
            if (node == null || !node.isObject()) throw new IOException("not an object");
            return node;
        } catch (IOException e) {
            throw new PaymentProviderUnavailableException("Razorpay " + endpoint + " returned an unreadable answer");
        }
    }

    /** Razorpay's {@code error.code} (e.g. BAD_REQUEST_ERROR), kept only when it looks like a code. */
    private static String errorCode(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            String code = text(JSON.readTree(body).path("error"), "code");
            return code != null && code.matches("[A-Z0-9_]{1,64}") ? code : null;
        } catch (IOException e) {
            return null;
        }
    }
}
