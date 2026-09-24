package com.agentstore.platform.execution;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Profile("!e2b")
public class FakeSandboxProvider implements SandboxProvider {

    private final List<SandboxSpec> created = new CopyOnWriteArrayList<>();
    private final Set<String> killed = ConcurrentHashMap.newKeySet();
    private final Set<String> exited = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean failNextCreate = new AtomicBoolean(false);

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        if (failNextCreate.getAndSet(false)) {
            throw new IllegalStateException("Simulated sandbox provider failure");
        }
        created.add(spec);
        return new SandboxHandle("fake-sandbox-" + UUID.randomUUID());
    }

    public void failNextCreate() {
        failNextCreate.set(true);
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

    @Override
    public SandboxStatus status(String sandboxId) {
        return killed.contains(sandboxId) || exited.contains(sandboxId) ? SandboxStatus.EXITED : SandboxStatus.RUNNING;
    }

    public void markExited(String sandboxId) {
        exited.add(sandboxId);
    }

    /** The spec used for the given run's sandbox (latest if created more than once). Tests read the raw token from it. */
    public SandboxSpec specForRun(UUID runId) {
        return created.stream()
                .filter(spec -> runId.toString().equals(spec.envVars().get("AGENT_RUN_ID")))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("No sandbox created for run " + runId));
    }
}
