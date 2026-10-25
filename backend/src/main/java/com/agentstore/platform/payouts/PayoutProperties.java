package com.agentstore.platform.payouts;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties("agentstore.money.payouts")
public record PayoutProperties(
        String provider,
        BigDecimal withdrawalMin,
        BigDecimal withdrawalDailyLimit,
        int validationAttemptsPerDay,
        Duration retryAfter,
        Duration stuckAfter,
        RazorpayX razorpayx,
        Fake fake) {

    public record RazorpayX(String keyId, String keySecret, String accountNumber, String webhookSecret, String baseUrl) {}

    public record Fake(String webhookSecret) {}
}
