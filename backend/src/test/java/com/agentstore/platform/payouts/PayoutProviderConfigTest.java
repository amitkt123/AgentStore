package com.agentstore.platform.payouts;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutProviderConfigTest {

    @EnableConfigurationProperties(PayoutProperties.class)
    @Import(PayoutProviderConfig.class)
    static class Cfg {}

    final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Cfg.class)
            .withPropertyValues(
                    "agentstore.money.payouts.withdrawal-min=100.00",
                    "agentstore.money.payouts.withdrawal-daily-limit=200000.00",
                    "agentstore.money.payouts.validation-attempts-per-day=3",
                    "agentstore.money.payouts.retry-after=PT2M",
                    "agentstore.money.payouts.stuck-after=PT24H");

    @Test
    void fakeProviderBean() {
        runner.withPropertyValues("agentstore.money.payouts.provider=fake",
                        "agentstore.money.payouts.fake.webhook-secret=s")
                .run(ctx -> assertThat(ctx.getBean(PayoutProvider.class)).isInstanceOf(FakePayoutProvider.class));
    }

    @Test
    void razorpayxProviderBeanWhenEverySecretIsSet() {
        runner.withPropertyValues("agentstore.money.payouts.provider=razorpayx",
                        "agentstore.money.payouts.razorpayx.key-id=rzp_test_abc",
                        "agentstore.money.payouts.razorpayx.key-secret=sec",
                        "agentstore.money.payouts.razorpayx.account-number=2323230000",
                        "agentstore.money.payouts.razorpayx.webhook-secret=whsec")
                .run(ctx -> assertThat(ctx.getBean(PayoutProvider.class)).isInstanceOf(RazorpayXPayoutProvider.class));
    }

    @Test
    void razorpayxWithBlankAccountNumberFailsStartup() {
        runner.withPropertyValues("agentstore.money.payouts.provider=razorpayx",
                        "agentstore.money.payouts.razorpayx.key-id=rzp_test_abc",
                        "agentstore.money.payouts.razorpayx.key-secret=sec",
                        "agentstore.money.payouts.razorpayx.account-number=",
                        "agentstore.money.payouts.razorpayx.webhook-secret=whsec")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    String all = allMessages(ctx.getStartupFailure());
                    assertThat(all).contains("RAZORPAYX_ACCOUNT_NUMBER").doesNotContain("whsec").doesNotContain("rzp_test_abc");
                });
    }

    @Test
    void unknownProviderFailsStartup() {
        runner.withPropertyValues("agentstore.money.payouts.provider=paypal")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(allMessages(ctx.getStartupFailure())).contains("Unknown payout provider: paypal");
                });
    }

    static String allMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append('\n');
        return sb.toString();
    }
}
