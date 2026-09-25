package com.agentstore.platform.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Lets the sandbox-runner find orphaned sandboxes on startup (WS5 spec §5). */
@RestController
public class InternalRunsController {

    private static final List<RunStatus> ACTIVE =
            List.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

    private final RunRepository runRepository;
    private final byte[] runnerSecret;

    public InternalRunsController(RunRepository runRepository,
                                  @Value("${agentstore.runner.secret:}") String runnerSecret) {
        this.runRepository = runRepository;
        this.runnerSecret = runnerSecret.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/internal/runs/active-ids")
    public Map<String, List<String>> activeIds(@RequestHeader(value = "X-Runner-Secret", required = false) String secret) {
        byte[] given = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (runnerSecret.length == 0 || !MessageDigest.isEqual(given, runnerSecret)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid runner secret");
        }
        return Map.of("runIds", runRepository.findByStatusIn(ACTIVE).stream().map(r -> r.getId().toString()).toList());
    }
}
