package com.agentstore.runner.api;

import com.agentstore.runner.backend.LogChunk;
import com.agentstore.runner.backend.SandboxState;
import com.agentstore.runner.sandbox.CreateSandboxCommand;
import com.agentstore.runner.sandbox.SandboxService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sandboxes")
public class SandboxController {

    private final SandboxService sandboxService;

    public SandboxController(SandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateSandboxResponse create(@Valid @RequestBody CreateSandboxRequest r) {
        return new CreateSandboxResponse(sandboxService.create(new CreateSandboxCommand(r.requestId(), r.imageRef(),
                r.command(), r.env(), r.allowOut(), r.cpu(), r.memoryMb(), r.timeoutSeconds())));
    }

    @GetMapping("/{id}")
    public SandboxStatusResponse status(@PathVariable String id) {
        SandboxState state = sandboxService.status(id);
        return new SandboxStatusResponse(state.phase().name(), state.exitCode());
    }

    @GetMapping("/{id}/logs")
    public LogChunkResponse logs(@PathVariable String id, @RequestParam(defaultValue = "0") long offset) {
        LogChunk chunk = sandboxService.logs(id, offset);
        return new LogChunkResponse(chunk.data(), chunk.nextOffset(), sandboxService.isFinished(id, chunk));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kill(@PathVariable String id) {
        sandboxService.kill(id);
    }
}
