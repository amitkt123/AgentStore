package com.agentstore.platform.payments;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentProviderConfig {

    @Bean
    PaymentProvider paymentProvider(PaymentProperties props) {
        String provider = props.provider() == null ? "" : props.provider().trim().toLowerCase();
        return switch (provider) {
            case "fake" -> new FakePaymentProvider(props.fake());
            case "razorpay" -> new RazorpayPaymentProvider(props.razorpay());
            default -> throw new IllegalStateException("Unknown payment provider: " + props.provider());
        };
    }
}
