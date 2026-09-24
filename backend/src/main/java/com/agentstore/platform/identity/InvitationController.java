package com.agentstore.platform.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Public endpoints behind the emailed /invite/{token} link. */
@RestController
@RequestMapping("/api/v1/invitations/{token}")
public class InvitationController {

    private final InvitationService invitationService;
    private final IdentityService identityService;

    public InvitationController(InvitationService invitationService, IdentityService identityService) {
        this.invitationService = invitationService;
        this.identityService = identityService;
    }

    @GetMapping
    public InvitationService.Preview preview(@PathVariable String token) {
        return invitationService.preview(token);
    }

    public record AcceptRequest(String fullName, String password) {}

    @PostMapping("/accept")
    public AuthResponse accept(@PathVariable String token, @RequestBody(required = false) AcceptRequest request) {
        AcceptRequest body = request == null ? new AcceptRequest(null, null) : request;
        OrgMembership membership = invitationService.accept(token, signedInUserId(), body.fullName(), body.password());
        return new AuthResponse(identityService.accessTokenFor(membership));
    }

    private static UUID signedInUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof JwtService.Claims claims ? claims.userId() : null;
    }
}
