package com.agentstore.platform.catalog;

import com.agentstore.platform.identity.JwtService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class MyAgentsController {

    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;

    public MyAgentsController(AgentRepository agentRepository, AgentVersionRepository agentVersionRepository) {
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
    }

    @GetMapping("/api/v1/agents/mine")
    @PreAuthorize("hasRole('DEVELOPER')")
    public List<MyAgentsResponse> mine() {
        var claims = (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return agentRepository.findByDeveloperOrgId(claims.orgId()).stream()
                .map(agent -> new MyAgentsResponse(agent.getId(), agent.getName(), agent.getCategory(),
                        versionsFor(agent.getId())))
                .toList();
    }

    private List<AgentVersionResponse> versionsFor(java.util.UUID agentId) {
        return java.util.Arrays.stream(VersionStatus.values())
                .flatMap(status -> agentVersionRepository.findByAgentIdAndStatus(agentId, status).stream())
                .map(AgentVersionResponse::from)
                .toList();
    }
}
