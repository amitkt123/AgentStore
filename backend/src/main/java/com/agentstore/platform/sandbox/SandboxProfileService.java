package com.agentstore.platform.sandbox;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.data.BindingOwnerType;
import com.agentstore.platform.data.DatasetBindingRepository;
import com.agentstore.platform.data.DatasetRepository;
import com.agentstore.platform.execution.RunLlmMode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Profile CRUD for the developer who owns the agent. Anything else is a 404, so ids of other orgs do not leak. */
@Service
public class SandboxProfileService {

    static final int MAX_SECRETS = 50;
    static final int MAX_SECRET_VALUE = 4096;
    static final int MAX_INPUT_BYTES = 64 * 1024;
    static final BigDecimal MAX_SPEND_CAP = new BigDecimal("999999999.99");
    private static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SandboxProfileRepository profiles;
    private final AgentRepository agents;
    private final FakeSecrets fakeSecrets;
    private final DatasetBindingRepository bindings;
    private final DatasetRepository datasets;

    public SandboxProfileService(SandboxProfileRepository profiles, AgentRepository agents, FakeSecrets fakeSecrets,
                                 DatasetBindingRepository bindings, DatasetRepository datasets) {
        this.profiles = profiles;
        this.agents = agents;
        this.fakeSecrets = fakeSecrets;
        this.bindings = bindings;
        this.datasets = datasets;
    }

    @Transactional
    public SandboxProfile create(UUID orgId, UUID agentId, ProfileInput in) {
        Agent agent = ownedAgent(orgId, agentId);
        String name = in.name() == null ? "" : in.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw SandboxApiException.invalid("name must be between 1 and 100 characters");
        }
        if (profiles.existsByAgentIdAndName(agent.getId(), name)) {
            throw nameTaken();
        }
        SandboxProfile profile = new SandboxProfile(agent.getId(), name);
        apply(profile, in, true);
        return save(profile);
    }

    /** A one-off profile from {@code POST /sandbox/runs}: same fields, hidden from listings. */
    @Transactional
    public SandboxProfile createInline(UUID orgId, UUID agentId, ProfileInput in) {
        Agent agent = ownedAgent(orgId, agentId);
        SandboxProfile profile = new SandboxProfile(agent.getId(), "inline-" + UUID.randomUUID().toString().substring(0, 8));
        profile.setInline(true);
        apply(profile, in, true);
        return save(profile);
    }

    @Transactional
    public SandboxProfile update(UUID orgId, UUID agentId, UUID profileId, ProfileInput in) {
        ownedAgent(orgId, agentId);
        SandboxProfile profile = owned(agentId, profileId);
        String name = in.name() == null ? profile.getName() : in.name().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw SandboxApiException.invalid("name must be between 1 and 100 characters");
        }
        if (!name.equals(profile.getName()) && profiles.existsByAgentIdAndName(agentId, name)) {
            throw nameTaken();
        }
        profile.setName(name);
        apply(profile, in, in.fakeSecrets() != null);
        return save(profile);
    }

    @Transactional
    public void delete(UUID orgId, UUID agentId, UUID profileId) {
        ownedAgent(orgId, agentId);
        SandboxProfile profile = owned(agentId, profileId);
        bindings.deleteAll(bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, profile.getId()));
        profiles.delete(profile);
    }

    @Transactional(readOnly = true)
    public List<ProfileView> list(UUID orgId, UUID agentId) {
        ownedAgent(orgId, agentId);
        return profiles.findByAgentIdAndInlineFalseOrderByNameAsc(agentId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ProfileView get(UUID orgId, UUID agentId, UUID profileId) {
        ownedAgent(orgId, agentId);
        return view(owned(agentId, profileId));
    }

    @Transactional(readOnly = true)
    public ProfileView view(SandboxProfile p) {
        List<ProfileView.BindingView> bound = bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.SANDBOX_PROFILE, p.getId())
                .stream().map(b -> new ProfileView.BindingView(b.getResourceName(), b.getDatasetId(),
                        datasets.findById(b.getDatasetId()).map(d -> d.getDisplayName()).orElse(null)))
                .sorted(java.util.Comparator.comparing(ProfileView.BindingView::resource)).toList();
        return new ProfileView(p.getId(), p.getAgentId(), p.getName(), readTree(p.getInputJson()),
                GatePolicy.parse(p.getGatePolicy()), p.getLlmMode(), p.getReplayRunId(),
                p.getLlmMockJson() == null ? null : readTree(p.getLlmMockJson()), p.getSpendCap(),
                fakeSecrets.names(p), bound, p.getCreatedAt(), p.getUpdatedAt());
    }

    private Agent ownedAgent(UUID orgId, UUID agentId) {
        return agents.findById(agentId).filter(a -> a.getDeveloperOrg().getId().equals(orgId))
                .orElseThrow(() -> SandboxApiException.notFound("Agent"));
    }

    private SandboxProfile owned(UUID agentId, UUID profileId) {
        return profiles.findById(profileId).filter(p -> p.getAgentId().equals(agentId))
                .orElseThrow(() -> SandboxApiException.notFound("Profile"));
    }

    private void apply(SandboxProfile profile, ProfileInput in, boolean replaceSecrets) {
        JsonNode input = in.input();
        if (input != null && !input.isNull()) {
            if (!input.isObject()) {
                throw SandboxApiException.invalid("input must be a JSON object");
            }
            if (input.toString().length() > MAX_INPUT_BYTES) {
                throw SandboxApiException.invalid("input may be at most 64 KB");
            }
        }
        profile.setInputJson(input == null || input.isNull() ? "{}" : input.toString());
        profile.setGatePolicy((in.gatePolicy() == null ? GatePolicy.manual() : in.gatePolicy()).toJson());
        if (in.llmMode() == RunLlmMode.REPLAY && in.replayRunId() == null) {
            throw SandboxApiException.invalid("llmMode REPLAY needs a replayRunId");
        }
        profile.setLlmMode(in.llmMode());
        profile.setReplayRunId(in.llmMode() == RunLlmMode.REPLAY ? in.replayRunId() : null);
        profile.setLlmMockJson(in.llmMock() == null || in.llmMock().isNull() ? null : in.llmMock().toString());
        if (in.spendCap() != null) {
            if (in.spendCap().signum() <= 0 || in.spendCap().compareTo(MAX_SPEND_CAP) > 0) {
                throw SandboxApiException.invalid("spendCap must be greater than 0 and at most " + MAX_SPEND_CAP);
            }
            profile.setSpendCap(in.spendCap().setScale(2, RoundingMode.HALF_UP));
        } else {
            profile.setSpendCap(null);
        }
        if (replaceSecrets) {
            validateSecrets(in.fakeSecrets());
            fakeSecrets.store(profile, in.fakeSecrets());
        }
    }

    private static void validateSecrets(Map<String, String> secrets) {
        if (secrets == null) {
            return;
        }
        if (secrets.size() > MAX_SECRETS) {
            throw SandboxApiException.invalid("at most " + MAX_SECRETS + " fake secrets");
        }
        secrets.forEach((name, value) -> {
            if (!SECRET_NAME.matcher(name).matches()) {
                throw SandboxApiException.invalid("fake secret names use letters, digits and underscore (max 64)");
            }
            if (value == null || value.length() > MAX_SECRET_VALUE) {
                throw SandboxApiException.invalid("a fake secret value may be at most " + MAX_SECRET_VALUE + " characters");
            }
        });
    }

    private SandboxProfile save(SandboxProfile profile) {
        try {
            return profiles.saveAndFlush(profile);
        } catch (DataIntegrityViolationException e) {
            throw nameTaken();
        }
    }

    private static SandboxApiException nameTaken() {
        return new SandboxApiException(HttpStatus.CONFLICT, "PROFILE_NAME_TAKEN", "This agent already has a profile with that name");
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
