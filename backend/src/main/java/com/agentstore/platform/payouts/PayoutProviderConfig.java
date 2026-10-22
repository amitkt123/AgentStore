package com.agentstore.platform.payouts;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PayoutProviderConfig {

    @Bean
    PayoutProvider payoutProvider(PayoutProperties props) {
        String provider = props.provider() == null ? "" : props.provider().trim().toLowerCase();
        return switch (provider) {
            case "fake" -> new FakePayoutProvider(props.fake());
            case "razorpayx" -> new RazorpayXPayoutProvider(props);
            default -> throw new IllegalStateException("Unknown payout provider: " + props.provider());
        };
    }
}
