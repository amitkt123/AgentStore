package com.agentstore.platform.build;

import java.util.UUID;

public record BuildFinishedEvent(UUID buildId, UUID versionId, String backend, boolean succeeded, String failureCode) {}
