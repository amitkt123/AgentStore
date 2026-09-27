package com.agentstore.platform.data;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sandbox-profiles/{profileId}/bindings")
@PreAuthorize("hasRole('DEVELOPER')")
public class SandboxBindingController {

    private final SandboxBindingService service;

    public SandboxBindingController(SandboxBindingService service) {
        this.service = service;
    }

    @PutMapping("/{resource}")
    public BindingService.BindingResponse bind(@PathVariable UUID profileId, @PathVariable String resource,
                                               @Valid @RequestBody BindingController.BindRequest request) {
        var claims = Caller.current();
        return service.bind(claims.orgId(), claims.userId(), profileId, resource, request.datasetId());
    }

    @DeleteMapping("/{resource}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable UUID profileId, @PathVariable String resource) {
        service.unbind(Caller.current().orgId(), profileId, resource);
    }

    @GetMapping
    public List<BindingService.BindingResponse> list(@PathVariable UUID profileId) {
        return service.list(Caller.current().orgId(), profileId);
    }
}
