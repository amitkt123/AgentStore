package com.agentstore.platform.payments;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ReconciliationIssueView(UUID id, LocalDate day, String kind, String providerRef, Object detail,
                                      Instant createdAt, Instant resolvedAt) {}
