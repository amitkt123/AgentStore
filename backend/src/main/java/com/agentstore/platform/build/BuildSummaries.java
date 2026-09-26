package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

@Component
public class BuildSummaries {

    static final Duration LOG_URL_TTL = Duration.ofMinutes(15);

    private final TemplateBuildRepository builds;
    private final ObjectStorage storage;

    public BuildSummaries(TemplateBuildRepository builds, ObjectStorage storage) {
        this.builds = builds;
        this.storage = storage;
    }

    public Optional<BuildSummary> latest(AgentVersion version) {
        return builds.findFirstByAgentVersionIdOrderByQueuedAtDesc(version.getId())
                .map(b -> new BuildSummary(b.getId(), b.getStatus(), b.getFailureCode(), b.getFailureReason(), logUrl(b)));
    }

    /** A pre-signed link to the log, or null while no log has been written yet (or the store is down). */
    String logUrl(TemplateBuild build) {
        if (build.getLogStorageKey() == null) {
            return null;
        }
        try {
            return storage.size(build.getLogStorageKey()).isPresent()
                    ? storage.presignGet(build.getLogStorageKey(), LOG_URL_TTL) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
