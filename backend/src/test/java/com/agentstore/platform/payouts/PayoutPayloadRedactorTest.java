package com.agentstore.platform.payouts;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutPayloadRedactorTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void bankAccountNumbersAreMaskedAnywhereInTheTree() {
        String raw = RazorpayXPayoutProviderTest.fixture("validation.completed-full.json");
        assertThat(raw).contains("765432123456789");

        String stored = PayoutPayloadRedactor.redact(bytes(raw));

        assertThat(stored).doesNotContain("765432123456789").contains("••••6789");
        // everything else is kept
        assertThat(stored).contains("HDFC0000053").contains("fav_00000000000002").contains("ASHA K SHARMA");
    }

    @Test
    void vpaAddressIsMasked() {
        String stored = PayoutPayloadRedactor.redact(bytes(
                RazorpayXPayoutProviderTest.fixture("validation.completed-vpa-full.json")));

        assertThat(stored).doesNotContain("asha.sharma@exampleupi").contains("••••eupi");
    }

    @Test
    void shortValuesAndUnrelatedFieldsAreLeftAlone() {
        String stored = PayoutPayloadRedactor.redact(bytes(
                "{\"account_number\":\"2323230000000001\",\"bank_account\":{\"account_number\":\"123\"},"
                        + "\"vpa\":{\"address\":null},\"address\":\"x@y\"}"));

        // a top-level account_number is ours (the RazorpayX source account), not under bank_account
        assertThat(stored).contains("2323230000000001").contains("\"account_number\":\"••••\"")
                .contains("\"address\":null").contains("\"address\":\"x@y\"");
    }
}
