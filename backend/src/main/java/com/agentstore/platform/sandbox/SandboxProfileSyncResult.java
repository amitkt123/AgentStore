package com.agentstore.platform.sandbox;

import java.util.List;

public record SandboxProfileSyncResult(String name, String action, List<String> unresolvedFixtures) {
}
