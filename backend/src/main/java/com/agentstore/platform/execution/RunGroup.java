package com.agentstore.platform.execution;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The four filter chips of the buyer's run list, each a set of run statuses. */
public enum RunGroup {
    NEEDS_YOU("needs_you", List.of(RunStatus.AWAITING_APPROVAL)),
    RUNNING("running", List.of(RunStatus.PENDING, RunStatus.PENDING_BUILD, RunStatus.RUNNING)),
    FINISHED("finished", List.of(RunStatus.COMPLETED)),
    STOPPED("stopped", List.of(RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.TIMED_OUT, RunStatus.EXPIRED));

    private final String key;
    private final List<RunStatus> statuses;

    RunGroup(String key, List<RunStatus> statuses) {
        this.key = key;
        this.statuses = statuses;
    }

    public String key() {
        return key;
    }

    public List<RunStatus> statuses() {
        return statuses;
    }

    public static RunGroup fromKey(String key) {
        for (RunGroup g : values()) {
            if (g.key.equals(key)) {
                return g;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown group: " + key);
    }

    public static RunGroup of(RunStatus status) {
        for (RunGroup g : values()) {
            if (g.statuses.contains(status)) {
                return g;
            }
        }
        throw new IllegalStateException("No group for " + status);
    }
}
