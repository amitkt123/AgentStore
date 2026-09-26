package com.agentstore.platform.build;

import java.util.UUID;

/** The latest build of a version, as the developer's version page shows it. */
public record BuildSummary(UUID buildId, BuildStatus status, String failureCode, String failureReason, String logUrl) {}
