package com.agentstore.platform.payouts;

/**
 * Marker for the payouts REST controllers. {@link PayoutApiErrors} applies only to controllers implementing this,
 * so its handlers never touch other controllers' error bodies. Every payouts controller must implement it.
 */
public interface PayoutApiController {}
