package com.agentstore.platform.payments;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class PaymentProviderConfig {

    @Bean
    PaymentProvider paymentProvider(PaymentProperties props, ObjectProvider<RestClient.Builder> restClientBuilders) {
        String provider = props.provider() == null ? "" : props.provider().trim().toLowerCase();
        return switch (provider) {
            case "fake" -> new FakePaymentProvider(props.fake());
            case "razorpay" -> new RazorpayPaymentProvider(
                    restClientBuilders.getIfAvailable(RestClient::builder), props);
            default -> throw new IllegalStateException("Unknown payment provider: " + props.provider());
        };
    }
}
