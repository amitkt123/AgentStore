package com.agentstore.platform.payments;

import com.agentstore.platform.payments.PaymentProvider.ProviderOrder;
import com.agentstore.platform.payments.PaymentProvider.ProviderPayment;
import com.agentstore.platform.payments.PaymentProvider.ProviderRefund;
import com.agentstore.platform.payments.PaymentProvider.ProviderSettlement;
import com.agentstore.platform.payments.PaymentProvider.ProviderVirtualAccount;
import com.agentstore.platform.payments.PaymentWebhookEvent.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RazorpayPaymentProviderTest {

    static final String BASE = "https://api.razorpay.test";
    static final String KEY_ID = "rzp_test_x";
    static final String KEY_SECRET = "key_secret_SHOULD_NOT_LEAK";
    static final String WEBHOOK_SECRET = "whsec_SHOULD_NOT_LEAK";
    static final ObjectMapper JSON = new ObjectMapper();

    MockRestServiceServer server;
    RazorpayPaymentProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new RazorpayPaymentProvider(builder, props(), false);
    }

    static PaymentProperties props() {
        return new PaymentProperties("razorpay", Set.of(PaymentMethod.values()), new BigDecimal("100.00"),
                new BigDecimal("100000.00"), 180, 15, 120,
                new PaymentProperties.Razorpay(KEY_ID, KEY_SECRET, WEBHOOK_SECRET, BASE),
                new PaymentProperties.Fake("f"));
    }

    static String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
    }

    static String fixture(String name) {
        try (InputStream in = RazorpayPaymentProviderTest.class.getResourceAsStream("/razorpay/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hmac(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void timeoutsArePinned() {
        assertThat(RazorpayPaymentProvider.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(RazorpayPaymentProvider.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(15));
        // The production constructor builds with its own request factory and still validates the secrets.
        assertThat(new RazorpayPaymentProvider(RestClient.builder(), props()).name()).isEqualTo("razorpay");
    }

    @Test
    void createOrderSendsPaiseAndRestrictsMethod() throws Exception {
        String receipt = UUID.randomUUID().toString();
        server.expect(once(), requestTo(BASE + "/v1/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth()))
                .andExpect(jsonPath("$.amount").value(50050))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.receipt").value(receipt))
                .andExpect(jsonPath("$.notes.purpose").value("TOPUP"))
                .andExpect(jsonPath("$.payment_capture").value(1))
                .andRespond(withSuccess(fixture("order.json"), MediaType.APPLICATION_JSON));

        ProviderOrder order = provider.createOrder(receipt, new BigDecimal("500.50"), PaymentMethod.UPI,
                Map.of("purpose", "TOPUP"));

        server.verify();
        assertThat(order.orderId()).isEqualTo("order_IluGWxBm9U8zJ8");
        Map<String, Object> c = order.checkout();
        assertThat(c).containsEntry("key", KEY_ID).containsEntry("order_id", "order_IluGWxBm9U8zJ8")
                .containsEntry("amount", 50050L).containsEntry("currency", "INR").containsEntry("name", "Karya");
        String json = JSON.writeValueAsString(c);
        assertThat(JSON.readTree(json).at("/config/display/blocks").size()).isEqualTo(1);
        assertThat(JSON.readTree(json).at("/config/display/blocks/upi/instruments/0/method").asText()).isEqualTo("upi");
        assertThat(JSON.readTree(json).at("/config/display/sequence/0").asText()).isEqualTo("block.upi");
        assertThat(JSON.readTree(json).at("/config/display/preferences/show_default_blocks").asBoolean(true)).isFalse();
        assertThat(json).doesNotContain(KEY_SECRET).doesNotContain(WEBHOOK_SECRET);
    }

    @Test
    void createOrderMapsCardAndNetbankingBlocks() throws Exception {
        for (var m : Map.of(PaymentMethod.CARD, "card", PaymentMethod.NETBANKING, "netbanking").entrySet()) {
            server.reset();
            server.expect(requestTo(BASE + "/v1/orders"))
                    .andRespond(withSuccess(fixture("order.json"), MediaType.APPLICATION_JSON));
            ProviderOrder order = provider.createOrder("r", new BigDecimal("100.00"), m.getKey(), Map.of());
            var blocks = JSON.readTree(JSON.writeValueAsString(order.checkout())).at("/config/display/blocks");
            assertThat(blocks.size()).isEqualTo(1);
            assertThat(blocks.get(m.getValue()).at("/instruments/0/method").asText()).isEqualTo(m.getValue());
        }
    }

    @Test
    void createVirtualAccountAsksForABankAccountReceiver() {
        UUID org = UUID.fromString("11111111-2222-3333-4444-555555555555");
        server.expect(requestTo(BASE + "/v1/virtual_accounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth()))
                .andExpect(jsonPath("$.receivers.types[0]").value("bank_account"))
                .andExpect(jsonPath("$.notes.org_id").value(org.toString()))
                .andRespond(withSuccess(fixture("virtual_account.json"), MediaType.APPLICATION_JSON));

        ProviderVirtualAccount va = provider.createVirtualAccount(org, "Acme Robotics");

        assertThat(va).isEqualTo(new ProviderVirtualAccount("va_DlGmm7jInLudH9", "2223330099089860", "RATN0VAAPIS",
                "Acme Robotics"));
    }

    @Test
    void refundSendsPaiseAndReceiptAndUpperCasesStatus() {
        String receipt = "9b7d0c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f";
        server.expect(requestTo(BASE + "/v1/payments/pay_29QQoUBi66xm2f/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.amount").value(12550))
                .andExpect(jsonPath("$.receipt").value(receipt))
                .andExpect(header("X-Refund-Idempotency", receipt))
                .andRespond(withSuccess(fixture("refund.json"), MediaType.APPLICATION_JSON));

        ProviderRefund r = provider.refund("pay_29QQoUBi66xm2f", new BigDecimal("125.50"), receipt);

        assertThat(r).isEqualTo(new ProviderRefund("rfnd_FP8QHiV938haTz", "pay_29QQoUBi66xm2f",
                new BigDecimal("125.50"), "PENDING", receipt, Instant.ofEpochSecond(1761091200)));
    }

    @Test
    void refundFindsExistingByReceipt() {
        server.expect(requestTo(BASE + "/v1/payments/pay_29QQoUBi66xm2f/refunds?count=100&skip=0"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", basicAuth()))
                .andRespond(withSuccess(fixture("payment_refunds.json"), MediaType.APPLICATION_JSON));
        Optional<ProviderRefund> found = provider.findRefund("pay_29QQoUBi66xm2f", "9b7d0c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
        assertThat(found).contains(new ProviderRefund("rfnd_FP8QHiV938haTz", "pay_29QQoUBi66xm2f",
                new BigDecimal("125.50"), "PROCESSED", "9b7d0c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f",
                Instant.ofEpochSecond(1761094800)));

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_29QQoUBi66xm2f/refunds?count=100&skip=0"))
                .andRespond(withSuccess(fixture("payment_refunds.json"), MediaType.APPLICATION_JSON));
        assertThat(provider.findRefund("pay_29QQoUBi66xm2f", "no-such-receipt")).isEmpty();
    }

    @Test
    void refundListWithoutItemsIsUnavailableNotNotFound() {
        // A 2xx without an items array must never read as "no such refund": the caller would refund again.
        server.expect(requestTo(BASE + "/v1/payments/pay_29QQoUBi66xm2f/refunds?count=100&skip=0"))
                .andRespond(withSuccess("{\"entity\":\"collection\",\"count\":0}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.findRefund("pay_29QQoUBi66xm2f", "9b7d0c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f"))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .hasMessageContaining("list payment refunds");

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_29QQoUBi66xm2f/refunds?count=100&skip=0"))
                .andRespond(withSuccess("{\"items\":{\"id\":\"SECRET\"}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.findRefund("pay_29QQoUBi66xm2f", "r-1"))
                .isInstanceOf(PaymentProviderUnavailableException.class).hasMessageNotContaining("SECRET");
    }

    @Test
    void dayFetchWithoutItemsIsUnavailableNotAnEmptyDay() {
        LocalDate day = LocalDate.of(2026, 10, 21);
        long from = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String q = "?from=" + from + "&to=" + (from + 86_399) + "&count=100&skip=0";
        server.expect(requestTo(BASE + "/v1/payments" + q))
                .andRespond(withSuccess("{\"entity\":\"collection\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.fetchPayments(day))
                .isInstanceOf(PaymentProviderUnavailableException.class).hasMessageContaining("list payments");

        server.reset();
        server.expect(requestTo(BASE + "/v1/refunds" + q))
                .andRespond(withSuccess("{\"items\":null}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.fetchRefunds(day))
                .isInstanceOf(PaymentProviderUnavailableException.class).hasMessageContaining("list refunds");

        server.reset();
        server.expect(requestTo(BASE + "/v1/settlements" + q))
                .andRespond(withSuccess("{\"entity\":\"collection\",\"count\":0,\"items\":[]}", MediaType.APPLICATION_JSON));
        assertThat(provider.fetchSettlements(day)).isEmpty();   // a real empty day is still empty
    }

    @Test
    void fetchPaymentReadsOneByIdAndTreatsARejectionAsUnknown() {
        server.expect(requestTo(BASE + "/v1/payments/pay_DlGmmE0kqRSuy6"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", basicAuth()))
                .andRespond(withSuccess("{\"id\":\"pay_DlGmmE0kqRSuy6\",\"entity\":\"payment\",\"amount\":2500000,"
                        + "\"status\":\"captured\",\"order_id\":null,\"virtual_account_id\":\"va_DlGmm7jInLudH9\"}",
                        MediaType.APPLICATION_JSON));
        assertThat(provider.fetchPayment("pay_DlGmmE0kqRSuy6")).contains(new ProviderPayment("pay_DlGmmE0kqRSuy6",
                null, new BigDecimal("25000.00"), "captured", "va_DlGmm7jInLudH9"));

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_nope")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"The id provided does not exist\"}}"));
        assertThat(provider.fetchPayment("pay_nope")).isEmpty();

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_x")).andRespond(withServerError());
        assertThatThrownBy(() -> provider.fetchPayment("pay_x")).isInstanceOf(PaymentProviderUnavailableException.class);
    }

    @Test
    void badCredentialsAreAnOutageNotARejection() {
        // A wrong or revoked key is our configuration problem: never "payment unknown", never "refund rejected".
        server.expect(requestTo(BASE + "/v1/payments/pay_y")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> provider.fetchPayment("pay_y")).isInstanceOf(PaymentProviderUnavailableException.class);

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_y/refund")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> provider.refund("pay_y", new BigDecimal("10.00"), "receipt-0123456789"))
                .isInstanceOf(PaymentProviderUnavailableException.class);
    }

    @Test
    void signatureVerification() throws Exception {
        byte[] body = fixture("payment.captured.json").getBytes(StandardCharsets.UTF_8);
        String sig = hmac(body, WEBHOOK_SECRET);

        assertThat(provider.verifyWebhook(body, Map.of("X-Razorpay-Signature", sig))).isTrue();
        assertThat(provider.verifyWebhook(body, Map.of("x-razorpay-signature", sig))).isTrue();

        byte[] tampered = fixture("payment.captured.json").replace("50050", "50051").getBytes(StandardCharsets.UTF_8);
        assertThat(provider.verifyWebhook(tampered, Map.of("X-Razorpay-Signature", sig))).isFalse();
        assertThat(provider.verifyWebhook(body, Map.of())).isFalse();
        assertThat(provider.verifyWebhook(body, Map.of("X-Razorpay-Signature", hmac(body, "other")))).isFalse();
        assertThat(provider.verifyWebhook(null, Map.of("X-Razorpay-Signature", sig))).isFalse();
    }

    @Test
    void parsesEachWebhookFixture() {
        assertThat(parse("payment.captured.json")).isEqualTo(new PaymentWebhookEvent("evt_1", Kind.PAYMENT_CAPTURED,
                "order_DESlLckIVRkHWj", "pay_DESlfW9H8K9uqM", null, null, new BigDecimal("500.50"), PaymentMethod.UPI,
                null));
        assertThat(parse("payment.failed.json")).isEqualTo(new PaymentWebhookEvent("evt_1", Kind.PAYMENT_FAILED,
                "order_DESm9TQZs6bOdY", "pay_DESmIfJmK8Lx7o", null, null, new BigDecimal("1000.00"), PaymentMethod.CARD,
                "Payment processing failed because of incorrect OTP"));
        assertThat(parse("order.paid.json")).isEqualTo(new PaymentWebhookEvent("evt_1", Kind.ORDER_PAID,
                "order_DESlLckIVRkHWj", "pay_DESlfW9H8K9uqM", null, null, new BigDecimal("500.50"),
                PaymentMethod.NETBANKING, null));
        assertThat(parse("virtual_account.credited.json")).isEqualTo(new PaymentWebhookEvent("evt_1",
                Kind.VIRTUAL_ACCOUNT_CREDITED, null, "pay_DlGmmE0kqRSuy6", null, "va_DlGmm7jInLudH9",
                new BigDecimal("25000.00"), PaymentMethod.BANK_TRANSFER, null));
        assertThat(parse("refund.processed.json")).isEqualTo(new PaymentWebhookEvent("evt_1", Kind.REFUND_PROCESSED,
                null, "pay_DESlfW9H8K9uqM", "rfnd_FP8QHiV938haTz", null, new BigDecimal("125.50"), null, null));
        assertThat(parse("refund.failed.json")).isEqualTo(new PaymentWebhookEvent("evt_1", Kind.REFUND_FAILED,
                null, "pay_G8VaL2Z68LRtDs", "rfnd_FP8R8EGjGbPkVb", null, new BigDecimal("100.00"), null, null));

        PaymentWebhookEvent other = parse("payment.authorized.json");
        assertThat(other.kind()).isEqualTo(Kind.OTHER);
        assertThat(other.method()).isNull();   // "wallet" has no PaymentMethod
        assertThat(parse("payment.captured.json").amount().scale()).isEqualTo(2);
    }

    @Test
    void malformedWebhookBodyIsRejectedWithoutQuotingIt() {
        byte[] body = "{\"event\": SECRET_BODY".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> provider.parseWebhook(body, Map.of("X-Razorpay-Event-Id", "evt_1")))
                .isInstanceOf(PaymentProviderRejectedException.class)
                .hasMessageNotContaining("SECRET_BODY");
    }

    private PaymentWebhookEvent parse(String name) {
        return provider.parseWebhook(fixture(name).getBytes(StandardCharsets.UTF_8),
                Map.of("X-Razorpay-Event-Id", "evt_1"));
    }

    @Test
    void fourXxIsRejectedFiveXxIsUnavailable() {
        String errorBody = "{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"SECRET_DESCRIPTION\","
                + "\"source\":\"business\",\"step\":\"payment_initiation\",\"reason\":\"input_validation_failed\"}}";
        server.expect(requestTo(BASE + "/v1/orders"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(errorBody));
        assertThatThrownBy(() -> provider.createOrder("SECRET_RECEIPT", new BigDecimal("1.00"), PaymentMethod.UPI, Map.of()))
                .isInstanceOf(PaymentProviderRejectedException.class)
                .hasMessageContaining("400").hasMessageContaining("BAD_REQUEST_ERROR")
                .hasMessageNotContaining("SECRET").hasMessageNotContaining(KEY_SECRET);

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_1/refund")).andRespond(withServerError().body(errorBody));
        assertThatThrownBy(() -> provider.refund("pay_1", new BigDecimal("1.00"), "SECRET_RECEIPT"))
                .isInstanceOf(PaymentProviderUnavailableException.class)
                .hasMessageContaining("500").hasMessageNotContaining("SECRET");

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_1/refund")).andRespond(withException(new SocketTimeoutException()));
        assertThatThrownBy(() -> provider.refund("pay_1", new BigDecimal("1.00"), "r"))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        server.reset();
        server.expect(requestTo(BASE + "/v1/payments/pay_1/refund")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> provider.refund("pay_1", new BigDecimal("1.00"), "r"))
                .isInstanceOf(PaymentProviderUnavailableException.class);

        server.reset();
        server.expect(requestTo(BASE + "/v1/orders")).andRespond(withSuccess("not json SECRET", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.createOrder("r", new BigDecimal("1.00"), PaymentMethod.UPI, Map.of()))
                .isInstanceOf(PaymentProviderUnavailableException.class).hasMessageNotContaining("SECRET");
    }

    @Test
    void fetchPaymentsPages() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 21);
        long from = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long to = from + 86_399;
        server.expect(requestTo(BASE + "/v1/payments?from=" + from + "&to=" + to + "&count=100&skip=0"))
                .andExpect(header("Authorization", basicAuth()))
                .andRespond(withSuccess(paymentsPage(0, 100), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments?from=" + from + "&to=" + to + "&count=100&skip=100"))
                .andRespond(withSuccess(paymentsPage(100, 3), MediaType.APPLICATION_JSON));

        List<ProviderPayment> payments = provider.fetchPayments(day);

        server.verify();
        assertThat(payments).hasSize(103);
        assertThat(payments.get(102)).isEqualTo(new ProviderPayment("pay_102", "order_102", new BigDecimal("1.02"),
                "captured", null));
    }

    @Test
    void fetchMapsPaymentsRefundsAndSettlements() {
        LocalDate day = LocalDate.of(2026, 10, 21);
        long from = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String q = "?from=" + from + "&to=" + (from + 86_399) + "&count=100&skip=0";
        server.expect(requestTo(BASE + "/v1/payments" + q))
                .andRespond(withSuccess(fixture("payments.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/refunds" + q))
                .andRespond(withSuccess(fixture("refunds.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/settlements" + q))
                .andRespond(withSuccess(fixture("settlements.json"), MediaType.APPLICATION_JSON));

        assertThat(provider.fetchPayments(day)).containsExactly(
                new ProviderPayment("pay_G8VQzjPLoAvm6D", "order_IluGWxBm9U8zJ8", new BigDecimal("500.50"), "captured", null),
                new ProviderPayment("pay_G8VaL2Z68LRtDs", "order_G8VXOp0qX2cmfS", new BigDecimal("200.00"), "failed", null),
                new ProviderPayment("pay_DlGmmE0kqRSuy6", null, new BigDecimal("25000.00"), "captured", "va_DlGmm7jInLudH9"));
        assertThat(provider.fetchRefunds(day)).containsExactly(
                new ProviderRefund("rfnd_FP8QHiV938haTz", "pay_29QQoUBi66xm2f", new BigDecimal("125.50"), "PROCESSED",
                        "9b7d0c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f", Instant.ofEpochSecond(1761094800)),
                new ProviderRefund("rfnd_FP8R8EGjGbPkVb", "pay_G8VQzjPLoAvm6D", new BigDecimal("50.00"), "PENDING", null,
                        Instant.ofEpochSecond(1761095000)));
        assertThat(provider.fetchSettlements(day)).containsExactly(
                new ProviderSettlement("setl_DGlQ1Rj8os78Ec", new BigDecimal("4.71")));
        server.verify();
    }

    private static String paymentsPage(int start, int n) throws Exception {
        List<Map<String, Object>> items = IntStream.range(start, start + n).mapToObj(i -> Map.<String, Object>of(
                "id", "pay_" + i, "entity", "payment", "amount", (long) i, "currency", "INR", "status", "captured",
                "order_id", "order_" + i, "method", "upi")).collect(Collectors.toList());
        return JSON.writeValueAsString(Map.of("entity", "collection", "count", n, "items", items));
    }

    @Test
    void blankSecretsFailFastWithoutQuotingValues() {
        PaymentProperties p = new PaymentProperties("razorpay", Set.of(), null, null, 0, 0, 0,
                new PaymentProperties.Razorpay(KEY_ID, " ", WEBHOOK_SECRET, BASE), null);
        assertThatThrownBy(() -> new RazorpayPaymentProvider(RestClient.builder(), p))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAZORPAY_KEY_SECRET")
                .hasMessageNotContaining(KEY_ID).hasMessageNotContaining(WEBHOOK_SECRET);
    }
}
