package com.agentstore.runner.sandbox;

import com.agentstore.runner.backend.IsolationBackend;
import com.agentstore.runner.backend.Labels;
import com.agentstore.runner.backend.LogChunk;
import com.agentstore.runner.backend.OwnedSandbox;
import com.agentstore.runner.backend.SandboxLaunch;
import com.agentstore.runner.backend.SandboxState;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Runner-side sandbox lifecycle: idempotent create keyed by requestId, lifetime clamp, labels, budget accounting. */
public class SandboxService {

    public static final int MAX_LOG_CHUNK = 256 * 1024;

    private final IsolationBackend backend;
    private final BudgetGuard budget;
    private final String runnerId;
    private final Duration maxLifetime;
    private final Clock clock;
    private final Map<String, String> sandboxByRequest = new ConcurrentHashMap<>();

    public SandboxService(IsolationBackend backend, BudgetGuard budget, String runnerId, Duration maxLifetime,
                          Clock clock) {
        this.backend = backend;
        this.budget = budget;
        this.runnerId = runnerId;
        this.maxLifetime = maxLifetime;
        this.clock = clock;
    }

    /** Serialized: one runner, low volume; keeps the "look up then create" step race-free. */
    public synchronized String create(CreateSandboxCommand cmd) {
        String cached = sandboxByRequest.get(cmd.requestId());
        if (cached != null) {
            return cached;
        }
        List<OwnedSandbox> owned = backend.listOwned();
        Optional<OwnedSandbox> existing = owned.stream()
                .filter(o -> cmd.requestId().equals(o.requestId()))
                .findFirst();
        if (existing.isPresent()) {
            sandboxByRequest.put(cmd.requestId(), existing.get().sandboxId());
            return existing.get().sandboxId();
        }

        budget.admit(owned.size());

        Instant now = clock.instant();
        int timeout = (int) Math.min(cmd.timeoutSeconds(), maxLifetime.toSeconds());
        Map<String, String> labels = Map.of(
                Labels.REQUEST_ID, cmd.requestId(),
                Labels.RUNNER_ID, runnerId,
                Labels.DEADLINE, String.valueOf(now.plusSeconds(timeout).getEpochSecond()));
        String sandboxId = backend.create(new SandboxLaunch(cmd.imageRef(), cmd.command(),
                cmd.env() == null ? Map.of() : cmd.env(),
                cmd.allowOut() == null ? List.of() : cmd.allowOut(),
                cmd.cpu(), cmd.memoryMb(), timeout, labels));
        budget.opened(sandboxId, now, timeout);
        sandboxByRequest.put(cmd.requestId(), sandboxId);
        return sandboxId;
    }

    public SandboxState status(String sandboxId) {
        return backend.status(sandboxId);
    }

    public LogChunk logs(String sandboxId, long offset) {
        return backend.readLogs(sandboxId, Math.max(0, offset), MAX_LOG_CHUNK);
    }

    /** Callers stop tailing when this is true: the sandbox is terminal and the chunk brought nothing new. */
    public boolean isFinished(String sandboxId, LogChunk chunk) {
        return chunk.data().isEmpty() && backend.status(sandboxId).isTerminal();
    }

    public void kill(String sandboxId) {
        backend.kill(sandboxId);
        sandboxByRequest.values().removeIf(sandboxId::equals);
        budget.closed(sandboxId, clock.instant());
    }
}
