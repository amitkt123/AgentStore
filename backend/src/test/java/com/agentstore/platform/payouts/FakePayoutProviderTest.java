package com.agentstore.platform.payouts;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePayoutProviderTest {

    final FakePayoutProvider provider = new FakePayoutProvider(new PayoutProperties.Fake("fake-payout-webhook-secret"));

    String fundAccount(String number, String holder) {
        String contact = provider.createContact(UUID.randomUUID(), holder);
        return provider.createFundAccount(contact,
                new PayoutProvider.FundAccountDetails(PayoutAccountType.BANK, number, "HDFC0001234", null, holder));
    }

    @Test
    void createPayoutTwiceWithSameKeyReturnsSamePayout() {
        String fa = fundAccount("12345678", "Asha Rao");
        PayoutProvider.ProviderPayout a = provider.createPayout(fa, new BigDecimal("500.00"), PayoutMode.IMPS, "k1");
        PayoutProvider.ProviderPayout b = provider.createPayout(fa, new BigDecimal("500.00"), PayoutMode.IMPS, "k1");
        assertThat(b.payoutId()).isEqualTo(a.payoutId());
        assertThat(a.status()).isEqualTo("PROCESSING");
        assertThat(a.fees()).isEqualByComparingTo("0.00");
        assertThat(provider.fetchPayouts(java.time.LocalDate.now(java.time.ZoneOffset.UTC))).hasSize(1);
        assertThat(provider.fetchPayout(a.payoutId())).isPresent();
    }

    @Test
    void validationNameRules() {
        assertThat(provider.validateFundAccount(fundAccount("12345678", "Asha Rao"), PayoutAccountType.BANK))
                .satisfies(v -> {
                    assertThat(v.status()).isEqualTo("COMPLETED");
                    assertThat(v.registeredName()).isEqualTo("Asha Rao");
                });
        assertThat(provider.validateFundAccount(fundAccount("12340000", "Asha Rao"), PayoutAccountType.BANK).registeredName())
                .isEqualTo("SOMEONE ELSE");
        String vpa = provider.createFundAccount(provider.createContact(UUID.randomUUID(), "Asha"),
                new PayoutProvider.FundAccountDetails(PayoutAccountType.VPA, null, null, "failme@upi", "Asha"));
        assertThat(provider.validateFundAccount(vpa, PayoutAccountType.VPA).status()).isEqualTo("FAILED");
        PayoutProvider.ProviderValidation pending =
                provider.validateFundAccount(fundAccount("12341111", "Asha Rao"), PayoutAccountType.BANK);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(provider.fetchValidation(pending.validationId()).orElseThrow().status()).isEqualTo("COMPLETED");
    }

    @Test
    void specialAmountsThrow() {
        String fa = fundAccount("12345678", "Asha Rao");
        assertThatThrownBy(() -> provider.createPayout(fa, new BigDecimal("999.99"), PayoutMode.IMPS, "a"))
                .isInstanceOf(PayoutInsufficientBalanceException.class);
        assertThatThrownBy(() -> provider.createPayout(fa, new BigDecimal("777.77"), PayoutMode.IMPS, "b"))
                .isInstanceOf(PayoutProviderUnavailableException.class);
    }

    @Test
    void signedEventVerifiesAndTamperedBodyDoesNot() {
        FakePayoutProvider.SignedEvent signed = provider.signedEvent(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED, "pout_1",
                Map.of("utr", "UTR123", "amount", "500.00"));
        assertThat(provider.verifyWebhook(signed.body(), signed.headers())).isTrue();
        PayoutWebhookEvent parsed = provider.parseWebhook(signed.body(), signed.headers());
        assertThat(parsed.kind()).isEqualTo(PayoutWebhookEvent.Kind.PAYOUT_PROCESSED);
        assertThat(parsed.payoutId()).isEqualTo("pout_1");
        assertThat(parsed.utr()).isEqualTo("UTR123");
        byte[] tampered = signed.body().clone();
        tampered[tampered.length / 2] ^= 1;
        assertThat(provider.verifyWebhook(tampered, signed.headers())).isFalse();
        assertThat(provider.verifyWebhook(signed.body(), Map.of())).isFalse();
    }
}
