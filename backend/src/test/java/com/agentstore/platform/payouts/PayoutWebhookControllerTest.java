package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.ledger.LedgerAccountKind;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutWebhookControllerTest extends WithdrawalTestSupport {

    private ResponseEntity<Map> postHook(String provider, byte[] body, Map<String, String> headers) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(h::set);
        return rest.exchange(url("/webhooks/payouts/" + provider), HttpMethod.POST, new HttpEntity<>(body, h),
                Map.class);
    }

    private ResponseEntity<Map> simulate(Org org, UserRole role, UUID withdrawalId, String outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("withdrawalId", withdrawalId.toString());
        body.put("outcome", outcome);
        return rest.exchange(url("/api/v1/dev/payouts/simulate"), HttpMethod.POST,
                new HttpEntity<>(body, auth(org, role)), Map.class);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from withdrawal where id = ?", String.class, id);
    }

    private long storedFor(String payoutId) {
        return jdbc.queryForObject("select count(*) from payment_webhook_event where provider = 'payouts:fake' "
                + "and payload->>'payoutId' = ?", Long.class, payoutId);
    }

    @Test
    void validEventIs200WithOutcome() {
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));
        String payoutId = jdbc.queryForObject("select provider_payout_id from withdrawal where id = ?", String.class, id);
        var e = fake().signedEvent(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED, payoutId,
                Map.of("amount", "400.00", "fees", "0.00", "utr", "UTRHTTP1"));

        var r = postHook("fake", e.body(), e.headers());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("outcome")).isEqualTo("PROCESSED");
        assertThat(status(id)).isEqualTo("PROCESSED");
        assertThat(postHook("fake", e.body(), e.headers()).getBody().get("outcome")).isEqualTo("DUPLICATE");
    }

    @Test
    void badSignatureIs400AndStoresNothing() {
        String payoutId = "fake_pout_sig_" + UUID.randomUUID();
        var e = fake().signedEvent(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED, payoutId, Map.of("amount", "100.00"));

        var r = postHook("fake", e.body(), Map.of("X-Fake-Signature", "deadbeef"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("BAD_SIGNATURE");
        assertThat(storedFor(payoutId)).isZero();
    }

    @Test
    void malformedSignedBodyIs400() {
        // Correctly signed, but not an event the fake can parse.
        var e = fake().signedEvent(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED, "x", Map.of("kind", "NOPE"));
        var r = postHook("fake", e.body(), e.headers());
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("MALFORMED_EVENT");
    }

    @Test
    void otherProviderPathIs404() {
        var e = fake().signedEvent(PayoutWebhookEvent.Kind.OTHER, "fake_pout_" + UUID.randomUUID(), Map.of());
        assertThat(postHook("razorpayx", e.body(), e.headers()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void oversizedBodyIs413() {
        byte[] big = new byte[PayoutWebhookController.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        assertThat(postHook("fake", big, Map.of()).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void simulateProcessedEndToEnd() {
        BigDecimal rzpx0 = platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE);
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));

        var r = simulate(org, UserRole.DEVELOPER, id, "PROCESSED");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().get("outcome")).isEqualTo("PROCESSED");
        Map<String, Object> w = (Map<String, Object>) r.getBody().get("withdrawal");
        assertThat(w.get("status")).isEqualTo("PROCESSED");
        assertThat(w.get("utr")).isNotNull();
        assertThat(status(id)).isEqualTo("PROCESSED");
        assertThat(platformBalance(LedgerAccountKind.RAZORPAYX_BALANCE).subtract(rzpx0)).isEqualByComparingTo("400.00");
        String payoutId = jdbc.queryForObject("select provider_payout_id from withdrawal where id = ?", String.class, id);
        assertThat(fake().fetchPayout(payoutId).orElseThrow().status()).isEqualTo("PROCESSED");

        // A processed withdrawal can still be reversed (the bank returns the money).
        var back = simulate(org, UserRole.DEVELOPER, id, "REVERSED");
        assertThat(back.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("REVERSED");
        assertThat(payableBalance(org)).isEqualByComparingTo("1000.00");
    }

    @Test
    void simulateOnFailedWithdrawalIs409() {
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));
        assertThat(simulate(org, UserRole.DEVELOPER, id, "FAILED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("FAILED");

        var r = simulate(org, UserRole.DEVELOPER, id, "PROCESSED");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody().get("code")).isEqualTo("WITHDRAWAL_NOT_SIMULATABLE");
        assertThat(status(id)).isEqualTo("FAILED");
    }

    @Test
    void simulateOtherOrgIs403AndAdminMayAct() {
        Org org = readyDeveloper("1000.00");
        Org other = readyDeveloper("10.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));

        assertThat(simulate(other, UserRole.DEVELOPER, id, "PROCESSED").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(simulate(org, UserRole.BUYER_ADMIN, id, "PROCESSED").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(id)).isEqualTo("PROCESSING");

        assertThat(simulate(other, UserRole.ADMIN, id, "PROCESSED").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status(id)).isEqualTo("PROCESSED");
    }

    @Test
    void simulateBadOutcomeIs400() {
        Org org = readyDeveloper("1000.00");
        UUID id = id(withdraw(org, UUID.randomUUID(), "400.00"));
        var r = simulate(org, UserRole.DEVELOPER, id, "PAID");
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().get("code")).isEqualTo("INVALID_FIELD");
    }
}
