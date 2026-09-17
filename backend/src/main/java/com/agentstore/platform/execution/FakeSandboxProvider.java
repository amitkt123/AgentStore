package com.agentstore.platform.execution;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
@Profile("!e2b")
public class FakeSandboxProvider implements SandboxProvider {

    private final List<SandboxSpec> created = new CopyOnWriteArrayList<>();
    private final Set<String> killed = ConcurrentHashMap.newKeySet();

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        created.add(spec);
        return new SandboxHandle("fake-sandbox-" + UUID.randomUUID());
    }

    @Override
    public void kill(String sandboxId) {
        killed.add(sandboxId);
    }

    public List<SandboxSpec> createdSpecs() {
        return created;
    }

    public boolean wasKilled(String sandboxId) {
        return killed.contains(sandboxId);
    }
}
