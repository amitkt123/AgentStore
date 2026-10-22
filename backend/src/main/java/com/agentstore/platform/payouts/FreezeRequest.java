package com.agentstore.platform.payouts;

/** Body of an admin freeze: why the org's money is being frozen (1 to 500 characters). */
public record FreezeRequest(String reason) {}
