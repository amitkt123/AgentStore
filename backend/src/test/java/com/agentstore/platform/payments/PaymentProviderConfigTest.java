package com.agentstore.platform.payments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProviderConfigTest {

    @EnableConfigurationProperties(PaymentProperties.class)
    @Import(PaymentProviderConfig.class)
    static class Cfg {}

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Cfg.class)
            .withPropertyValues(
                    "agentstore.money.payments.methods=UPI,CARD",
                    "agentstore.money.payments.topup-min=100.00",
                    "agentstore.money.payments.topup-daily-limit=1000.00",
                    "agentstore.money.payments.refund-window-days=180",
                    "agentstore.money.payments.bill-due-days=15",
                    "agentstore.money.payments.webhook-rate-per-minute=120",
                    "agentstore.money.payments.fake.webhook-secret=s");

    @Test
    void fakeProviderBean() {
        runner.withPropertyValues("agentstore.money.payments.provider=fake")
                .run(ctx -> assertThat(ctx.getBean(PaymentProvider.class)).isInstanceOf(FakePaymentProvider.class));
    }

    @Test
    void razorpayProviderBeanWhenEverySecretIsSet() {
        runner.withPropertyValues("agentstore.money.payments.provider=razorpay",
                        "agentstore.money.payments.razorpay.key-id=rzp_test_abc",
                        "agentstore.money.payments.razorpay.key-secret=sec",
                        "agentstore.money.payments.razorpay.webhook-secret=whsec",
                        "agentstore.money.payments.razorpay.base-url=https://api.razorpay.com")
                .run(ctx -> assertThat(ctx.getBean(PaymentProvider.class)).isInstanceOf(RazorpayPaymentProvider.class));
    }

    @Test
    void razorpayWithBlankSecretFailsNamingTheVariable() {
        runner.withPropertyValues("agentstore.money.payments.provider=razorpay",
                        "agentstore.money.payments.razorpay.key-id=rzp_test_abc",
                        "agentstore.money.payments.razorpay.key-secret=",
                        "agentstore.money.payments.razorpay.webhook-secret=whsec")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    String all = allMessages(ctx.getStartupFailure());
                    assertThat(all).contains("RAZORPAY_KEY_SECRET").doesNotContain("whsec").doesNotContain("rzp_test_abc");
                });
    }

    @Test
    void unknownProviderFails() {
        runner.withPropertyValues("agentstore.money.payments.provider=paypal")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(allMessages(ctx.getStartupFailure())).contains("Unknown payment provider: paypal");
                });
    }

    static String allMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append('\n');
        return sb.toString();
    }
}
