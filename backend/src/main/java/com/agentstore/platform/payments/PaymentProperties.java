package com.agentstore.platform.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Set;

@ConfigurationProperties("agentstore.money.payments")
public record PaymentProperties(
        String provider,
        Set<PaymentMethod> methods,
        BigDecimal topupMin,
        BigDecimal topupDailyLimit,
        int refundWindowDays,
        int billDueDays,
        int webhookRatePerMinute,
        Razorpay razorpay,
        Fake fake) {

    public record Razorpay(String keyId, String keySecret, String webhookSecret, String baseUrl) {}

    public record Fake(String webhookSecret) {}
}
