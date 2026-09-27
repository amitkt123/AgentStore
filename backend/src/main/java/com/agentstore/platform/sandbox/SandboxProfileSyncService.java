package com.agentstore.platform.sandbox;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.Dataset;
import com.agentstore.platform.data.DatasetBinding;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetCheckService;
import com.agentstore.platform.data.DatasetPurpose;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies {@code fixtures/profiles/*.yaml} to {@code sandbox_profile}, one file per {@link SandboxProfileSyncRequest}
 * entry. Deliberately does not reuse {@link com.agentstore.platform.data.SandboxBindingService#bind}: that method
 * rejects a binding outright on a failed schema check, which is right for an interactive portal edit but wrong for
 * unattended CI automation — a sync must never fail over a fixture that merely doesn't match the manifest yet; it
 * records the check result for later ({@link ProfileStatusService} recomputes it live regardless) and moves on.
 */
@Service
public class SandboxProfileSyncService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SandboxProfileRepository profiles;
    private final AgentRepository agents;
    private final AgentVersionRepository versions;
    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final DatasetCheckService checks;
    private final ManifestParser manifestParser;

    public SandboxProfileSyncService(SandboxProfileRepository profiles, AgentRepository agents,
                                     AgentVersionRepository versions, DatasetRepository datasets,
                                     DatasetBindingRepository bindings, DatasetCheckService checks,
                                     ManifestParser manifestParser) {
        this.profiles = profiles;
        this.agents = agents;
        this.versions = versions;
        this.datasets = datasets;
        this.bindings = bindings;
        this.checks = checks;
        this.manifestParser = manifestParser;
    }

    @Transactional
    public List<SandboxProfileSyncResult> sync(UUID orgId, UUID agentId, List<SandboxProfileSyncRequest> entries) {
        ownedAgent(orgId, agentId);
        List<SandboxProfileSyncResult> results = new ArrayList<>();
        for (SandboxProfileSyncRequest entry : entries) {
            results.add(syncOne(orgId, agentId, entry));
        }
        return results;
    }

    private SandboxProfileSyncResult syncOne(UUID orgId, UUID agentId, SandboxProfileSyncRequest entry) {
        SandboxProfile existing = profiles.findByAgentIdAndName(agentId, entry.name()).orElse(null);
        if (existing != null && existing.getSource() == ProfileSource.REPO
                && entry.contentHash().equals(existing.getRepoSourceHash())) {
            return new SandboxProfileSyncResult(entry.name(), "skipped", List.of());
        }
        String action = existing == null ? "created" : "updated";
        SandboxProfile profile = existing == null ? new SandboxProfile(agentId, entry.name()) : existing;
        profile.setDescription(entry.description());
        profile.setInputJson(entry.input() == null || entry.input().isNull() ? "{}" : entry.input().toString());
        profile.setGatePolicy((entry.gatePolicy() == null ? GatePolicy.manual() : entry.gatePolicy()).toJson());
        profile.setLlmMode(entry.llmMode());
        profile.setReplayRunId(null);
        profile.setLlmMockJson(null);
        profile.setSpendCap(entry.spendCap());
        profile.setSource(ProfileSource.REPO);
        profile.setRepoSourceHash(entry.contentHash());
        profile.setTrialEnabled(false);
        profile.setNoRealDataAttestedBy(null);
        profile.setNoRealDataAttestedAt(null);
        profile = profiles.saveAndFlush(profile);

        Manifest manifest = latestManifest(agentId).orElse(null);
        List<String> unresolved = new ArrayList<>();
        for (SandboxProfileSyncRequest.BindingEntry b : entry.bindings()) {
            Optional<Dataset> found = datasets.findFirstByOwnerOrgIdAndPurposeAndDisplayNameAndDeletedAtIsNullOrderByCreatedAtDesc(
                    orgId, DatasetPurpose.FIXTURE, b.fixture());
            if (found.isEmpty()) {
                unresolved.add(b.fixture());
                continue;
            }
            Dataset dataset = found.get();
            ResourceDecl decl = manifest == null ? null : manifest.resource(b.resource()).orElse(null);
            String checkJson = json(checks.check(dataset, decl));
            DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.SANDBOX_PROFILE,
                    profile.getId(), b.resource()).orElse(null);
            if (binding == null) {
                binding = new DatasetBinding(BindingOwnerType.SANDBOX_PROFILE, profile.getId(), b.resource(),
                        dataset.getId(), checkJson, null);
            } else {
                binding.setDatasetId(dataset.getId());
                binding.setCheckResult(checkJson);
                binding.setBoundAt(Instant.now());
            }
            bindings.saveAndFlush(binding);
        }
        return new SandboxProfileSyncResult(entry.name(), action, unresolved);
    }

    private Optional<Manifest> latestManifest(UUID agentId) {
        return versions.findFirstByAgentIdOrderByCreatedAtDesc(agentId).map(v -> manifestParser.parse(v.getManifestJson()));
    }

    private Agent ownedAgent(UUID orgId, UUID agentId) {
        return agents.findById(agentId).filter(a -> a.getDeveloperOrg().getId().equals(orgId))
                .orElseThrow(() -> SandboxApiException.notFound("Agent"));
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
