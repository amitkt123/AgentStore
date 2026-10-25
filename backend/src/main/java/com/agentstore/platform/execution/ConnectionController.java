package com.agentstore.platform.execution;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgMembershipId;
import com.agentstore.platform.identity.OrgMembershipRepository;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgRoles;
import com.agentstore.platform.manifest.ManifestParser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
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
    private final OrgMembershipRepository memberships;
    private final ConnectionLimits connectionLimits;

    public ConnectionController(ConnectionRepository connectionRepository, AgentRepository agentRepository,
                                 AgentVersionRepository agentVersionRepository, OrgRepository orgRepository,
                                 ManifestParser manifestParser, SecretService secretService,
                                 OrgMembershipRepository memberships, ConnectionLimits connectionLimits) {
        this.connectionRepository = connectionRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.orgRepository = orgRepository;
        this.manifestParser = manifestParser;
        this.secretService = secretService;
        this.memberships = memberships;
        this.connectionLimits = connectionLimits;
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

        Connection connection = new Connection(null, buyerOrg, agent, request.grantedScopes(), request.spendCap(),
                ConnectionStatus.ACTIVE);
        connection.setAgentVersion(liveVersion);
        connection.setMonthlyLimit(request.monthlyLimit());
        if (request.approverUserIds() != null) {
            connection.setApproverUserIds(validatedApprovers(claims.orgId(), request.approverUserIds()));
        }
        return ConnectionResponse.from(connectionRepository.save(connection));
    }

    @PatchMapping("/{connectionId}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    @Transactional
    public ConnectionResponse update(@PathVariable java.util.UUID connectionId,
                                     @Valid @RequestBody UpdateConnectionRequest request) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!connection.getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connection not owned by caller's org");
        }
        if (Boolean.TRUE.equals(request.clearMonthlyLimit())) {
            connection.setMonthlyLimit(null);
        } else if (request.monthlyLimit() != null) {
            connection.setMonthlyLimit(request.monthlyLimit());
        }
        if (request.approverUserIds() != null) {
            connection.setApproverUserIds(validatedApprovers(claims.orgId(), request.approverUserIds()));
        }
        return ConnectionResponse.from(connectionRepository.save(connection));
    }

    /** Each approver must be a buyer admin or buyer member of the org: the decision endpoints require one of those roles. */
    private Set<java.util.UUID> validatedApprovers(java.util.UUID orgId, List<java.util.UUID> userIds) {
        Set<java.util.UUID> approvers = new java.util.HashSet<>(userIds);
        for (java.util.UUID userId : approvers) {
            boolean approver = memberships.findById(new OrgMembershipId(userId, orgId))
                    .map(m -> OrgRoles.canDecideGates(m.getRole())).orElse(false);
            if (!approver) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Not an approver in this workspace: " + userId);
            }
        }
        return approvers;
    }

    @PostMapping("/{connectionId}/secrets")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public void storeSecret(@PathVariable java.util.UUID connectionId, @RequestBody StoreSecretRequest request) {
        Connection connection = connectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connection not found"));
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (!connection.getBuyerOrg().getId().equals(claims.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Connection not owned by caller's org");
        }
        secretService.store(connectionId, request.name(), request.value());
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_MEMBER')")
    public List<ConnectionSummaryResponse> mine() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return connectionRepository.findByBuyerOrgId(claims.orgId()).stream()
                .map(c -> ConnectionSummaryResponse.from(c, connectionLimits.monthToDate(c.getId())))
                .toList();
    }

    public record StoreSecretRequest(String name, String value) {
    }
}
