package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/connections")
public class ConnectionController {

    private final ConnectionRepository connectionRepository;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final OrgRepository orgRepository;
    private final ManifestParser manifestParser;
    private final SecretService secretService;

    public ConnectionController(ConnectionRepository connectionRepository, AgentRepository agentRepository,
                                 AgentVersionRepository agentVersionRepository, OrgRepository orgRepository,
                                 ManifestParser manifestParser, SecretService secretService) {
        this.connectionRepository = connectionRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestParser = manifestParser;
        this.secretService = secretService;
    }

    @PostMapping
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public ConnectionResponse create(@Valid @RequestBody CreateConnectionRequest request) {
        Agent agent = agentRepository.findById(request.agentId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        var liveVersion = agentVersionRepository
                .findFirstByAgentIdAndStatusOrderByApprovedAtDesc(agent.getId(), VersionStatus.LIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Agent has no LIVE version"));

        var manifest = manifestParser.parse(liveVersion.getManifestJson());
        Set<String> declaredResources = manifest.scopes().stream()
                .map(com.agentstore.platform.manifest.Scope::resource).collect(Collectors.toSet());
        for (String requested : request.grantedScopes()) {
            if (!declaredResources.contains(requested)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Scope not declared by manifest: " + requested);
            }
        }

        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Org buyerOrg = orgRepository.getReferenceById(claims.orgId());

        Connection saved = connectionRepository.save(new Connection(null, buyerOrg, agent, request.grantedScopes(),
                request.spendCap(), ConnectionStatus.ACTIVE));
        return ConnectionResponse.from(saved);
    }

    @PostMapping("/{connectionId}/secrets")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public void storeSecret(@PathVariable java.util.UUID connectionId, @RequestBody StoreSecretRequest request) {
        secretService.store(connectionId, request.name(), request.value());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public List<ConnectionSummaryResponse> mine() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return connectionRepository.findByBuyerOrgId(claims.orgId()).stream()
                .map(ConnectionSummaryResponse::from)
                .toList();
    }

    public record StoreSecretRequest(String name, String value) {
    }
}
