package com.agentstore.platform.execution;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Profile("!runner")
public class FakeSandboxProvider implements SandboxProvider {

    private final List<SandboxSpec> created = new CopyOnWriteArrayList<>();
    private final Set<String> killed = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> exited = new ConcurrentHashMap<>();
    private final Set<String> unknown = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean failNextCreate = new AtomicBoolean(false);
    private final AtomicReference<String> capacityCode = new AtomicReference<>();

    @Override
    public SandboxHandle create(SandboxSpec spec) {
        String code = capacityCode.getAndSet(null);
        if (code != null) {
            throw new SandboxCapacityException(code);
        }
        if (failNextCreate.getAndSet(false)) {
            throw new IllegalStateException("Simulated sandbox provider failure");
        }
        created.add(spec);
        return new SandboxHandle("fake-sandbox-" + UUID.randomUUID());
    }

    public void failNextCreate() {
        failNextCreate.set(true);
    }

    public void failNextCreateWithCapacity(String code) {
        capacityCode.set(code);
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
        if (unknown.contains(sandboxId)) {
            return SandboxStatus.UNKNOWN;
        }
        if (killed.contains(sandboxId)) {
            return SandboxStatus.GONE;
        }
        Integer code = exited.get(sandboxId);
        return code == null ? SandboxStatus.RUNNING : SandboxStatus.exited(code);
    }

    public void markExited(String sandboxId) {
        markExited(sandboxId, 0);
    }

    public void markExited(String sandboxId, int exitCode) {
        exited.put(sandboxId, exitCode);
    }

    public void markUnknown(String sandboxId) {
        unknown.add(sandboxId);
    }

    /** The spec used for the given run's sandbox (latest if created more than once). Tests read the raw token from it. */
    public SandboxSpec specForRun(UUID runId) {
        return created.stream()
                .filter(spec -> runId.toString().equals(spec.envVars().get("AGENTSTORE_RUN_ID")))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("No sandbox created for run " + runId));
    }
}
