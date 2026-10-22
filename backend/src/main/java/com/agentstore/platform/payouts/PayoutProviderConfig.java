package com.agentstore.platform.payouts;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PayoutProviderConfig {

    @Bean
    PayoutProvider payoutProvider(PayoutProperties props, ObjectProvider<RestClient.Builder> restClientBuilders) {
        String provider = props.provider() == null ? "" : props.provider().trim().toLowerCase();
        return switch (provider) {
            case "fake" -> new FakePayoutProvider(props.fake());
            case "razorpayx" -> new RazorpayXPayoutProvider(restClientBuilders.getIfAvailable(RestClient::builder), props);
            default -> throw new IllegalStateException("Unknown payout provider: " + props.provider());
        };
    }
}
