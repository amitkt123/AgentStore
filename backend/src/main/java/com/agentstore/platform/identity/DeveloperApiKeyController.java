package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Key management is JWT-only: ApiKeyScope keeps a key itself away from these endpoints. */
@RestController
@RequestMapping("/api/v1/developer/api-keys")
public class DeveloperApiKeyController {

    private final DeveloperApiKeyService service;

    public DeveloperApiKeyController(DeveloperApiKeyService service) {
        this.service = service;
    }

    public record CreateRequest(@NotBlank @Size(max = 100) String name) {}

    @PostMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.CREATED)
    public DeveloperApiKeyService.Created create(@Valid @RequestBody CreateRequest request) {
        var claims = claims();
        return service.create(claims.orgId(), claims.userId(), request.name());
    }

    @GetMapping
    @PreAuthorize("hasRole('DEVELOPER')")
    public List<DeveloperApiKeyService.Summary> list() {
        return service.list(claims().orgId());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('DEVELOPER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        service.revoke(claims().orgId(), id);
    }

    private static JwtService.Claims claims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
