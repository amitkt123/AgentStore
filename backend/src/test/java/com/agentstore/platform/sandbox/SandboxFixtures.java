package com.agentstore.platform.sandbox;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.Tier;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.execution.RunTokenService;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.identity.UserRole;
import com.agentstore.platform.manifest.ManifestParser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Shared setup for sandbox tests: developer orgs with a DRAFT built version, profiles, sandbox runs, developer JWTs. */
@Component
public class SandboxFixtures {

    public record Dev(Org org, Agent agent, AgentVersion version) {}

    private final OrgRepository orgs;
    private final AgentRepository agents;
    private final AgentVersionRepository versions;
    private final SandboxProfileRepository profiles;
    private final RunRepository runs;
    private final JwtService jwt;
    private final JdbcTemplate jdbc;
    private final ManifestParser parser;

    public SandboxFixtures(OrgRepository orgs, AgentRepository agents, AgentVersionRepository versions,
                           SandboxProfileRepository profiles, RunRepository runs, JwtService jwt, JdbcTemplate jdbc,
                           ManifestParser parser) {
        this.orgs = orgs;
        this.agents = agents;
        this.versions = versions;
        this.profiles = profiles;
        this.runs = runs;
        this.jwt = jwt;
        this.jdbc = jdbc;
        this.parser = parser;
    }

    /** A new DEVELOPER org with one agent and one DRAFT version (image "template-v1") of the given test manifest. */
    public Dev developer(String manifestFile) {
        Org org = orgs.save(new Org(null, "DevCo-" + UUID.randomUUID(), OrgType.DEVELOPER));
        return agentIn(org, manifestFile);
    }

    /** Another agent (with its own DRAFT version) in an existing developer org. */
    public Dev agentIn(Org org, String manifestFile) {
        Agent agent = agents.save(new Agent(null, org, "Agent-" + UUID.randomUUID(), "reconciliation", "d"));
        return new Dev(org, agent, versionOf(agent, "1.0.0", manifestFile, VersionStatus.DRAFT));
    }

    public AgentVersion versionOf(Agent agent, String number, String manifestFile, VersionStatus status) {
        return versions.save(new AgentVersion(null, agent, number, readManifest(manifestFile), "template-v1", status,
                Tier.TIER_A, "cl"));
    }

    public HttpHeaders bearer(Org org, UserRole role) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwt.issue(UUID.randomUUID(), org.getId(), role));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public SandboxProfile profile(Dev dev, String name) {
        return profiles.save(new SandboxProfile(dev.agent().getId(), name));
    }

    /** A sandbox run row straight in the database, as the service would leave it (no launch). */
    public Run sandboxRun(Dev dev, SandboxProfile profile, RunStatus status) {
        BigDecimal cap = parser.parse(dev.version().getManifestJson()).costEnvelope().maxPerRun();
        Run run = new Run(null, null, dev.version(), status, cap, BigDecimal.ZERO, "sbx-" + UUID.randomUUID(),
                RunTokenService.hash("tok-" + UUID.randomUUID()));
        run.setMode(RunMode.SANDBOX);
        run.setInitiatedByOrgId(dev.org().getId());
        run.setSandboxProfileId(profile == null ? null : profile.getId());
        run.setStartedAt(Instant.now());
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        return runs.save(run);
    }

    public void verify(Org org) {
        jdbc.update("UPDATE org SET developer_verified_at = now() WHERE id = ?", org.getId());
    }

    public static String readManifest(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/manifest/" + name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
