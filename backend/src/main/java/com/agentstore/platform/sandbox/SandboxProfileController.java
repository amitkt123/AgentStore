package com.agentstore.platform.sandbox;

import com.agentstore.platform.data.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agents/{agentId}/sandbox-profiles")
@PreAuthorize("hasRole('DEVELOPER')")
public class SandboxProfileController {

    private final SandboxProfileService service;
    private final SandboxProfileSyncService syncService;

    public SandboxProfileController(SandboxProfileService service, SandboxProfileSyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileView create(@PathVariable UUID agentId, @RequestBody ProfileInput body) {
        return service.view(service.create(Caller.current().orgId(), agentId, body));
    }

    @GetMapping
    public List<ProfileView> list(@PathVariable UUID agentId) {
        return service.list(Caller.current().orgId(), agentId);
    }

    @GetMapping("/{profileId}")
    public ProfileView get(@PathVariable UUID agentId, @PathVariable UUID profileId) {
        return service.get(Caller.current().orgId(), agentId, profileId);
    }

    @PutMapping("/{profileId}")
    public ProfileView update(@PathVariable UUID agentId, @PathVariable UUID profileId, @RequestBody ProfileInput body) {
        var caller = Caller.current();
        return service.view(service.update(caller.orgId(), caller.userId(), agentId, profileId, body));
    }

    @PostMapping("/sync")
    public List<SandboxProfileSyncResult> sync(@PathVariable UUID agentId,
                                                @RequestBody List<SandboxProfileSyncRequest> body) {
        return syncService.sync(Caller.current().orgId(), agentId, body);
    }

    @DeleteMapping("/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID agentId, @PathVariable UUID profileId) {
        service.delete(Caller.current().orgId(), agentId, profileId);
    }
}
