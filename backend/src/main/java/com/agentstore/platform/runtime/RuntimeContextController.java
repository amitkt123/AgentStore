package com.agentstore.platform.runtime;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/runtime/v1")
public class RuntimeContextController {

    private final RunRepository runRepository;
    private final ObjectMapper objectMapper;

    public RuntimeContextController(RunRepository runRepository, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/context")
    public ContextResponse context() throws JsonProcessingException {
        RunPrincipal principal = RunPrincipal.current();
        Run run = runRepository.findById(principal.runId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found"));
        Map<String, Object> input = objectMapper.readValue(run.getInputJson(), new TypeReference<>() {});
        var scopes = principal.manifest().scopes().stream()
                .filter(s -> principal.granted(s.type(), s.resource()))
                .map(s -> new ContextResponse.GrantedScope(s.type(), s.resource(), s.requiresApproval()))
                .toList();
        BigDecimal remaining = run.getCostCap().subtract(run.getTotalCharged()).max(BigDecimal.ZERO);
        return new ContextResponse(run.getId(), run.getMode(),
                new ContextResponse.AgentVersionInfo(run.getAgentVersion().getId(), run.getAgentVersion().getVersionNumber()),
                input, scopes,
                new ContextResponse.Limits(run.getDeadlineAt(), remaining, principal.manifest().costEnvelope().currency()));
    }
}
