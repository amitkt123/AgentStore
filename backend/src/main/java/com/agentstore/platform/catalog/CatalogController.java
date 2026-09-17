package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agents")
public class CatalogController {

    private final AgentRepository agentRepository;
    private final OrgRepository orgRepository;

    public CatalogController(AgentRepository agentRepository, OrgRepository orgRepository) {
        this.agentRepository = agentRepository;
        this.orgRepository = orgRepository;
    }

    @PostMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public AgentResponse create(@Valid @RequestBody CreateAgentRequest request) {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Org developerOrg = orgRepository.getReferenceById(claims.orgId());
        Agent saved = agentRepository.save(
                new Agent(null, developerOrg, request.name(), request.category(), request.description()));
        return AgentResponse.from(saved);
    }
}
