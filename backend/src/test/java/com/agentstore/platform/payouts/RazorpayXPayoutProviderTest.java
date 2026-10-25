package com.agentstore.platform.payouts;

import com.agentstore.platform.payouts.PayoutProvider.FundAccountDetails;
import com.agentstore.platform.payouts.PayoutProvider.ProviderPayout;
import com.agentstore.platform.payouts.PayoutProvider.ProviderValidation;
import com.agentstore.platform.payouts.PayoutWebhookEvent.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class RazorpayXPayoutProviderTest {

    static final String BASE = "https://api.razorpayx.test";
    static final String KEY_ID = "rzp_test_x";
    static final String KEY_SECRET = "key_secret_SHOULD_NOT_LEAK";
    static final String ACCOUNT = "2323230099887766";
    static final String WEBHOOK_SECRET = "whsec_SHOULD_NOT_LEAK";
    static final String BANK_NUMBER = "123456789012";

    MockRestServiceServer server;
    RazorpayXPayoutProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new RazorpayXPayoutProvider(builder, props(), false);
    }

    static PayoutProperties props() {
        return new PayoutProperties("razorpayx", new BigDecimal("100.00"), new BigDecimal("200000.00"), 3,
                Duration.ofMinutes(2), Duration.ofHours(24),
                new PayoutProperties.RazorpayX(KEY_ID, KEY_SECRET, ACCOUNT, WEBHOOK_SECRET, BASE),
                new PayoutProperties.Fake("f"));
    }

    static String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((KEY_ID + ":" + KEY_SECRET).getBytes(StandardCharsets.UTF_8));
    }

    static String fixture(String name) {
        try (InputStream in = RazorpayXPayoutProviderTest.class.getResourceAsStream("/razorpayx/" + name)) {
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
        assertThat(RazorpayXPayoutProvider.CONNECT_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
        assertThat(RazorpayXPayoutProvider.READ_TIMEOUT).isEqualTo(Duration.ofSeconds(15));
        assertThat(new RazorpayXPayoutProvider(RestClient.builder(), props()).name()).isEqualTo("razorpayx");
    }

    @Test
    void createContactSendsVendorWithOrgReference() {
        UUID org = UUID.randomUUID();
        server.expect(once(), requestTo(BASE + "/v1/contacts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth()))
                .andExpect(jsonPath("$.name").value("Asha Sharma"))
                .andExpect(jsonPath("$.type").value("vendor"))
                .andExpect(jsonPath("$.reference_id").value(org.toString()))
                .andRespond(withSuccess(fixture("contact.json"), MediaType.APPLICATION_JSON));
        assertThat(provider.createContact(org, "Asha Sharma")).isEqualTo("cont_00000000000001");
        server.verify();
    }

    @Test
    void createBankFundAccount() {
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth()))
                .andExpect(jsonPath("$.contact_id").value("cont_1"))
                .andExpect(jsonPath("$.account_type").value("bank_account"))
                .andExpect(jsonPath("$.bank_account.name").value("Asha Sharma"))
                .andExpect(jsonPath("$.bank_account.ifsc").value("HDFC0001234"))
                .andExpect(jsonPath("$.bank_account.account_number").value(BANK_NUMBER))
                .andRespond(withSuccess(fixture("fund_account.json"), MediaType.APPLICATION_JSON));
        String id = provider.createFundAccount("cont_1", new FundAccountDetails(PayoutAccountType.BANK, BANK_NUMBER,
                "HDFC0001234", null, "Asha Sharma"));
        assertThat(id).isEqualTo("fa_00000000000001");
        server.verify();
    }

    @Test
    void createVpaFundAccount() {
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts"))
                .andExpect(jsonPath("$.account_type").value("vpa"))
                .andExpect(jsonPath("$.vpa.address").value("asha@okhdfc"))
                .andRespond(withSuccess(fixture("fund_account.json"), MediaType.APPLICATION_JSON));
        provider.createFundAccount("cont_1", new FundAccountDetails(PayoutAccountType.VPA, null, null, "asha@okhdfc", "Asha"));
        server.verify();
    }

    @Test
    void validateBankSendsOneRupeePenny() {
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts/validations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth()))
                .andExpect(jsonPath("$.account_number").value(ACCOUNT))
                .andExpect(jsonPath("$.amount").value(100))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.fund_account.id").value("fa_1"))
                .andRespond(withSuccess(fixture("validation.created.json"), MediaType.APPLICATION_JSON));
        ProviderValidation v = provider.validateFundAccount("fa_1", PayoutAccountType.BANK);
        assertThat(v.validationId()).isEqualTo("fav_00000000000001");
        assertThat(v.status()).isEqualTo("PENDING");
        assertThat(v.registeredName()).isNull();
        server.verify();
    }

    @Test
    void validateVpaSendsNoAmount() {
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts/validations"))
                .andExpect(jsonPath("$.amount").doesNotExist())
                .andExpect(jsonPath("$.fund_account.id").value("fa_1"))
                .andRespond(withSuccess(fixture("validation.created.json"), MediaType.APPLICATION_JSON));
        provider.validateFundAccount("fa_1", PayoutAccountType.VPA);
        server.verify();
    }

    @Test
    void fetchValidationMapsCompletedAndMissing() {
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts/validations/fav_1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", basicAuth()))
                .andRespond(withSuccess(fixture("validation.fetched-completed.json"), MediaType.APPLICATION_JSON));
        ProviderValidation v = provider.fetchValidation("fav_1").orElseThrow();
        assertThat(v.status()).isEqualTo("COMPLETED");
        assertThat(v.registeredName()).isEqualTo("ASHA K SHARMA");

        server.reset();
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts/validations/fav_2"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"BAD_REQUEST_ERROR\"}}"));
        assertThat(provider.fetchValidation("fav_2")).isEmpty();
        server.verify();
    }

    @Test
    void createPayoutSendsPaiseAndIdempotencyHeader() {
        String key = UUID.randomUUID().toString();
        server.expect(once(), requestTo(BASE + "/v1/payouts"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basicAuth()))
                .andExpect(header("X-Payout-Idempotency", key))
                .andExpect(jsonPath("$.account_number").value(ACCOUNT))
                .andExpect(jsonPath("$.fund_account_id").value("fa_1"))
                .andExpect(jsonPath("$.amount").value(123456))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.mode").value("IMPS"))
                .andExpect(jsonPath("$.purpose").value("payout"))
                .andExpect(jsonPath("$.queue_if_low_balance").value(false))
                .andExpect(jsonPath("$.reference_id").value(key))
                .andRespond(withSuccess(fixture("payout.json"), MediaType.APPLICATION_JSON));
        ProviderPayout p = provider.createPayout("fa_1", new BigDecimal("1234.56"), PayoutMode.IMPS, key);
        assertThat(p.payoutId()).isEqualTo("pout_00000000000001");
        assertThat(p.idempotencyKey()).isEqualTo("9b7d0c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
        assertThat(p.amount()).isEqualByComparingTo("1234.56");
        assertThat(p.fees()).isEqualByComparingTo("5.90");
        assertThat(p.status()).isEqualTo("PROCESSING");
        assertThat(p.utr()).isNull();
        assertThat(p.createdAt()).isEqualTo(Instant.ofEpochSecond(1597078866L));
        server.verify();
    }

    @Test
    void upiModeIsSent() {
        server.expect(once(), requestTo(BASE + "/v1/payouts"))
                .andExpect(jsonPath("$.mode").value("UPI"))
                .andRespond(withSuccess(fixture("payout.json"), MediaType.APPLICATION_JSON));
        provider.createPayout("fa_1", new BigDecimal("100"), PayoutMode.UPI, "k");
        server.verify();
    }

    @Test
    void fractionalPaiseIsRefused() {
        assertThatThrownBy(() -> provider.createPayout("fa_1", new BigDecimal("1.234"), PayoutMode.IMPS, "k"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fetchPayoutMapsAndMissing() {
        server.expect(once(), requestTo(BASE + "/v1/payouts/pout_1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(fixture("payout.json"), MediaType.APPLICATION_JSON));
        assertThat(provider.fetchPayout("pout_1")).isPresent();
        server.reset();
        server.expect(once(), requestTo(BASE + "/v1/payouts/pout_2")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(provider.fetchPayout("pout_2")).isEmpty();
        server.verify();
    }

    @Test
    void payoutStatusesMap() {
        Map<String, String> expected = Map.of("queued", "QUEUED", "pending", "QUEUED", "scheduled", "QUEUED",
                "processing", "PROCESSING", "processed", "PROCESSED", "reversed", "REVERSED", "failed", "FAILED",
                "rejected", "FAILED", "cancelled", "FAILED");
        expected.forEach((raw, mapped) -> {
            server.reset();
            server.expect(once(), requestTo(BASE + "/v1/payouts/p"))
                    .andRespond(withSuccess(fixture("payout.json").replace("\"processing\"", "\"" + raw + "\""),
                            MediaType.APPLICATION_JSON));
            assertThat(provider.fetchPayout("p").orElseThrow().status()).as(raw).isEqualTo(mapped);
        });
    }

    @Test
    void fetchPayoutsPages() {
        LocalDate day = LocalDate.of(2026, 10, 20);
        long from = day.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1;
        String one = fixture("payout.json");
        String full = "{\"entity\":\"collection\",\"count\":100,\"items\":["
                + String.join(",", IntStream.range(0, 100).mapToObj(i -> one).toList()) + "]}";
        String shortPage = "{\"entity\":\"collection\",\"count\":1,\"items\":[" + one + "]}";
        server.expect(once(), requestTo(BASE + "/v1/payouts?account_number=" + ACCOUNT + "&from=" + from + "&to=" + to
                        + "&count=100&skip=0"))
                .andExpect(header("Authorization", basicAuth()))
                .andRespond(withSuccess(full, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/v1/payouts?account_number=" + ACCOUNT + "&from=" + from + "&to=" + to
                        + "&count=100&skip=100"))
                .andRespond(withSuccess(shortPage, MediaType.APPLICATION_JSON));
        List<ProviderPayout> all = provider.fetchPayouts(day);
        assertThat(all).hasSize(101);
        server.verify();
    }

    @Test
    void insufficientBalanceMaps() {
        server.expect(once(), requestTo(BASE + "/v1/payouts"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body(fixture("error.insufficient.json")));
        assertThatThrownBy(() -> provider.createPayout("fa_1", new BigDecimal("500"), PayoutMode.IMPS, "k"))
                .isInstanceOf(PayoutInsufficientBalanceException.class);
    }

    @Test
    void otherClientErrorIsRejectedAndQuotesNothing() {
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body(fixture("error.bad.json")));
        assertThatThrownBy(() -> provider.createFundAccount("c", new FundAccountDetails(PayoutAccountType.BANK, BANK_NUMBER,
                "X", null, "A")))
                .isInstanceOf(PayoutProviderRejectedException.class)
                .hasMessageContaining("400").hasMessageContaining("BAD_REQUEST_ERROR")
                .hasMessageNotContaining(BANK_NUMBER).hasMessageNotContaining("IFSC");
    }

    @Test
    void unauthorizedIsUnavailable() {
        server.expect(once(), requestTo(BASE + "/v1/payouts/p")).andRespond(withUnauthorizedRequest());
        assertThatThrownBy(() -> provider.fetchPayout("p")).isInstanceOf(PayoutProviderUnavailableException.class);
        server.reset();
        server.expect(once(), requestTo(BASE + "/v1/payouts/p")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> provider.fetchPayout("p")).isInstanceOf(PayoutProviderUnavailableException.class);
    }

    @Test
    void serverErrorAndTimeoutAreUnavailable() {
        server.expect(once(), requestTo(BASE + "/v1/payouts")).andRespond(withServerError());
        assertThatThrownBy(() -> provider.createPayout("fa", BigDecimal.TEN, PayoutMode.IMPS, "k"))
                .isInstanceOf(PayoutProviderUnavailableException.class);
        server.reset();
        server.expect(once(), requestTo(BASE + "/v1/payouts")).andRespond(request -> {
            throw new SocketTimeoutException("Read timed out");
        });
        assertThatThrownBy(() -> provider.createPayout("fa", BigDecimal.TEN, PayoutMode.IMPS, "k"))
                .isInstanceOf(PayoutProviderUnavailableException.class);
    }

    @Test
    void webhookSignatureVerifiesAndTamperedFails() throws Exception {
        byte[] body = fixture("payout.processed.json").getBytes(StandardCharsets.UTF_8);
        String sig = hmac(body, WEBHOOK_SECRET);
        assertThat(provider.verifyWebhook(body, Map.of("X-Razorpay-Signature", sig))).isTrue();
        assertThat(provider.verifyWebhook(body, Map.of("x-razorpay-signature", sig))).isTrue();
        byte[] tampered = fixture("payout.processed.json").replace("12345", "99999").getBytes(StandardCharsets.UTF_8);
        assertThat(provider.verifyWebhook(tampered, Map.of("X-Razorpay-Signature", sig))).isFalse();
        assertThat(provider.verifyWebhook(body, Map.of("X-Razorpay-Signature", hmac(body, "other")))).isFalse();
        assertThat(provider.verifyWebhook(body, Map.of())).isFalse();
    }

    Map<String, String> eventHeaders() {
        return Map.of("x-razorpay-event-id", "evt_123");
    }

    PayoutWebhookEvent parse(String fixtureName) {
        return provider.parseWebhook(fixture(fixtureName).getBytes(StandardCharsets.UTF_8), eventHeaders());
    }

    @Test
    void eachEventMaps() {
        PayoutWebhookEvent processed = parse("payout.processed.json");
        assertThat(processed.kind()).isEqualTo(Kind.PAYOUT_PROCESSED);
        assertThat(processed.eventId()).isEqualTo("evt_123");
        assertThat(processed.payoutId()).isEqualTo("pout_00000000000001");
        assertThat(processed.amount()).isEqualByComparingTo("123.45");
        assertThat(processed.fees()).isEqualByComparingTo("5.90");
        assertThat(processed.utr()).isEqualTo("928374650123");

        assertThat(parse("payout.reversed.json").kind()).isEqualTo(Kind.PAYOUT_REVERSED);

        PayoutWebhookEvent failed = parse("payout.failed.json");
        assertThat(failed.kind()).isEqualTo(Kind.PAYOUT_FAILED);
        assertThat(failed.failureReason()).isEqualTo("Beneficiary bank is offline");
        assertThat(parse("payout.rejected.json").kind()).isEqualTo(Kind.PAYOUT_FAILED);

        PayoutWebhookEvent done = parse("validation.completed.json");
        assertThat(done.kind()).isEqualTo(Kind.VALIDATION_COMPLETED);
        assertThat(done.validationId()).isEqualTo("fav_00000000000001");
        assertThat(done.registeredName()).isEqualTo("ASHA K SHARMA");
        assertThat(parse("validation.completed-altkey.json").kind()).isEqualTo(Kind.VALIDATION_COMPLETED);

        PayoutWebhookEvent invalid = parse("validation.completed-invalid.json");
        assertThat(invalid.kind()).isEqualTo(Kind.VALIDATION_FAILED);
        assertThat(invalid.validationId()).isEqualTo("fav_00000000000001");

        assertThat(parse("validation.failed.json").kind()).isEqualTo(Kind.VALIDATION_FAILED);
        assertThat(parse("payout.initiated.json").kind()).isEqualTo(Kind.OTHER);
    }

    @Test
    void feesAlreadyIncludeTaxAndTaxIsOnlyAFallback() {
        // RazorpayX's fees (590) already include tax (90): the fee we absorb is 5.90, not 6.80.
        assertThat(parse("payout.processed.json").fees()).isEqualByComparingTo("5.90");
        String noFees = fixture("payout.processed.json").replace("\"fees\": 590,", "");
        PayoutWebhookEvent e = provider.parseWebhook(noFees.getBytes(StandardCharsets.UTF_8), eventHeaders());
        assertThat(e.fees()).isEqualByComparingTo("0.90");
        String neither = noFees.replace("\"tax\": 90,", "");
        assertThat(provider.parseWebhook(neither.getBytes(StandardCharsets.UTF_8), eventHeaders()).fees()).isNull();
    }

    @Test
    void payoutEventCarriesReferenceId() {
        assertThat(parse("payout.processed.json").referenceId()).isEqualTo("9b7d0c4e-1a2b-4c3d-8e9f-0a1b2c3d4e5f");
    }

    @Test
    void validationCompletedWithoutStatusUsesAccountStatus() {
        String active = fixture("validation.completed.json").replace("\"status\": \"completed\",", "");
        PayoutWebhookEvent ok = provider.parseWebhook(active.getBytes(StandardCharsets.UTF_8), eventHeaders());
        assertThat(ok.kind()).isEqualTo(Kind.VALIDATION_COMPLETED);
        assertThat(ok.registeredName()).isEqualTo("ASHA K SHARMA");

        String invalid = active.replace("\"account_status\": \"active\"", "\"account_status\": \"invalid\"");
        PayoutWebhookEvent bad = provider.parseWebhook(invalid.getBytes(StandardCharsets.UTF_8), eventHeaders());
        assertThat(bad.kind()).isEqualTo(Kind.VALIDATION_FAILED);
        assertThat(bad.registeredName()).isNull();
    }

    @Test
    void malformedWebhookIsRejected() {
        assertThatThrownBy(() -> provider.parseWebhook("nope".getBytes(StandardCharsets.UTF_8), eventHeaders()))
                .isInstanceOf(PayoutProviderRejectedException.class);
    }

    @Test
    void logsNeverContainAccountNumberOrSecrets(CapturedOutput output) {
        server.expect(once(), requestTo(BASE + "/v1/fund_accounts"))
                .andRespond(withSuccess(fixture("fund_account.json"), MediaType.APPLICATION_JSON));
        provider.createFundAccount("c", new FundAccountDetails(PayoutAccountType.BANK, BANK_NUMBER, "HDFC0001234", null, "A"));
        server.reset();
        server.expect(once(), requestTo(BASE + "/v1/payouts"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body(fixture("error.bad.json")));
        assertThatThrownBy(() -> provider.createPayout("fa", BigDecimal.TEN, PayoutMode.IMPS, "k"))
                .hasMessageNotContaining(ACCOUNT).hasMessageNotContaining(KEY_SECRET);
        assertThat(output.getAll()).doesNotContain(BANK_NUMBER).doesNotContain(ACCOUNT)
                .doesNotContain(KEY_SECRET).doesNotContain(WEBHOOK_SECRET);
    }
}
