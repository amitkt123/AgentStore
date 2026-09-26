package com.agentstore.platform.build;

import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Rebuilds a platform-built version when the configured backend changed since its image was built (spec §4 step 7). */
@Service
public class RebuildService {

    private final TemplateBuildRepository builds;
    private final AgentRepository agents;
    private final String backend;

    public RebuildService(TemplateBuildRepository builds, AgentRepository agents,
                          @Value("${agentstore.runner.backend:e2b}") String backend) {
        this.builds = builds;
        this.agents = agents;
        this.backend = backend;
    }

    public boolean needsRebuild(AgentVersion version) {
        if (version.getBundle() == null) {
            return false;   // hand-supplied image (legacy path): nothing to rebuild from
        }
        return builds.findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc(version.getId(), BuildStatus.SUCCEEDED)
                .filter(b -> b.getBundle().getId().equals(version.getBundle().getId()))
                .map(b -> !b.getBackend().equals(backend))
                .orElse(false);
    }

    /**
     * Queues one rebuild of the version's current bundle on the configured backend, unless one is already queued or
     * running. The agent row is locked first (the order finalize and BuildTransitions use), so two triggers racing
     * here cannot both find the version idle and queue two builds.
     */
    @Transactional
    public void queueRebuild(AgentVersion version) {
        agents.findByIdForUpdate(version.getAgent().getId()).orElseThrow();
        if (builds.existsByAgentVersionIdAndBackendAndStatusIn(version.getId(), backend,
                List.of(BuildStatus.QUEUED, BuildStatus.BUILDING))) {
            return;
        }
        TemplateBuild last = builds.findFirstByAgentVersionIdAndStatusOrderByFinishedAtDesc(version.getId(),
                BuildStatus.SUCCEEDED).orElseThrow();
        TemplateBuild rebuild = builds.save(new TemplateBuild(version, version.getBundle(), backend, last.getRuntime(),
                last.getEntrypoint()));
        rebuild.setLogStorageKey("builds/" + rebuild.getId() + ".log");
    }
}
