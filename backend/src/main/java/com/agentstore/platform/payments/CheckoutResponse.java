package com.agentstore.platform.payments;

import java.util.Map;

public record CheckoutResponse(PaymentOrderView order, String provider, String providerOrderId,
                               Map<String, Object> checkout) {}
