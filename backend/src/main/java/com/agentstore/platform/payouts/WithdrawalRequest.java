package com.agentstore.platform.payouts;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code requestId} is chosen by the client; repeating it returns the first withdrawal. */
public record WithdrawalRequest(UUID requestId, BigDecimal amount) {}
