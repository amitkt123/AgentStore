package com.agentstore.platform.payments;

import java.math.BigDecimal;
import java.util.List;

/** How much the org can refund now, and how that (or the asked) amount splits across its payments. */
public record RefundableResponse(BigDecimal refundable, List<RefundService.Part> parts) {}
