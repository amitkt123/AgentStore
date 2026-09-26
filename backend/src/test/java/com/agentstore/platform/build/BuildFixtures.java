package com.agentstore.platform.build;

import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.*;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Builds and versions in known states, with the bundle object stored, for pipeline tests. */
@Component
public class BuildFixtures {

    private final OrgRepository orgs;
    private final UserRepository users;
    private final AgentRepository agents;
    private final AgentVersionRepository versions;
    private final AgentBundleRepository bundles;
    private final TemplateBuildRepository builds;
    private final ObjectStorage storage;
    private final JdbcTemplate jdbc;

    public BuildFixtures(OrgRepository orgs, UserRepository users, AgentRepository agents, AgentVersionRepository versions,
                         AgentBundleRepository bundles, TemplateBuildRepository builds, ObjectStorage storage,
                         JdbcTemplate jdbc) {
        this.orgs = orgs;
        this.users = users;
        this.agents = agents;
        this.versions = versions;
        this.bundles = bundles;
        this.builds = builds;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    public record Built(Org org, Agent agent, AgentVersion version, AgentBundle bundle, TemplateBuild build) {}

    /**
     * The test DB is shared by every test class: finish any build other tests left QUEUED or BUILDING, so they can't
     * block dispatch ("one at a time") or be dispatched instead of this test's build.
     */
    public void clearActiveBuilds() {
        jdbc.update("update template_build set status = 'FAILED', failure_code = 'SUPERSEDED' "
                + "where status in ('QUEUED', 'BUILDING')");
    }

    /** A DRAFT version with a stored bundle and one QUEUED build on {@code backend}. */
    public Built queued(String backend) {
        Org org = orgs.save(new Org(null, "BuildCo " + UUID.randomUUID(), OrgType.DEVELOPER));
        User user = users.save(new User(null, "builder-" + UUID.randomUUID() + "@example.com", null, "Builder"));
        Agent agent = agents.save(new Agent(null, org, "Recon", "finance", "d"));
        byte[] zip = TestBundles.python("1.0.0");
        UUID bundleId = UUID.randomUUID();
        String key = "bundles/" + agent.getId() + "/" + bundleId + ".zip";
        storage.put(key, zip);
        AgentBundle bundle = bundles.save(new AgentBundle(bundleId, agent, user.getId(), key, TestBundles.sha256(zip),
                zip.length, BundleStatus.UPLOADED));
        AgentVersion version = new AgentVersion(null, agent, "1.0.0", TestBundles.manifest("1.0.0"), null,
                VersionStatus.DRAFT, null, null);
        version.setBundle(bundle);
        version.setBuildStatus(BuildStatus.QUEUED);
        version = versions.save(version);
        TemplateBuild build = new TemplateBuild(version, bundle, backend, "python-3.12", "agent/main.py");
        build = builds.save(build);
        build.setLogStorageKey("builds/" + build.getId() + ".log");
        build = builds.save(build);
        return new Built(org, agent, version, bundle, build);
    }

    /** Like {@link #queued} but already dispatched: BUILDING since {@code startedAt}, one attempt made. */
    public Built building(String backend, Instant startedAt, int attempts) {
        Built b = queued(backend);
        b.build().setStatus(BuildStatus.BUILDING);
        b.build().setStartedAt(startedAt);
        b.build().setAttempts(attempts);
        TemplateBuild build = builds.save(b.build());
        return new Built(b.org(), b.agent(), b.version(), b.bundle(), build);
    }

    public TemplateBuild saveBuild(TemplateBuild build) {
        return builds.save(build);
    }

    public TemplateBuild reload(TemplateBuild build) {
        return builds.findById(build.getId()).orElseThrow();
    }

    public AgentVersion reload(AgentVersion version) {
        return versions.findById(version.getId()).orElseThrow();
    }
}
