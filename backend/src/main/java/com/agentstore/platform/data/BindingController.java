package com.agentstore.platform.data;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/connections/{connectionId}/bindings")
public class BindingController {

    public record BindRequest(@NotNull UUID datasetId) {
    }

    private final BindingService bindingService;

    public BindingController(BindingService bindingService) {
        this.bindingService = bindingService;
    }

    @PutMapping("/{resource}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    public BindingService.BindingResponse bind(@PathVariable UUID connectionId, @PathVariable String resource,
                                               @Valid @RequestBody BindRequest request) {
        var claims = Caller.current();
        return bindingService.bind(claims.orgId(), claims.userId(), connectionId, resource, request.datasetId());
    }

    @DeleteMapping("/{resource}")
    @PreAuthorize("hasRole('BUYER_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unbind(@PathVariable UUID connectionId, @PathVariable String resource) {
        bindingService.unbind(Caller.current().orgId(), connectionId, resource);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('BUYER_ADMIN','BUYER_APPROVER')")
    public List<BindingService.BindingResponse> list(@PathVariable UUID connectionId) {
        return bindingService.list(Caller.current().orgId(), connectionId);
    }
}
