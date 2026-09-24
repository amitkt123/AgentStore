package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;

    public AuthController(UserRepository userRepository, OrgMembershipRepository membershipRepository,
                          IdentityService identityService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
    }

    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {
        OrgMembership membership = identityService.signup(request);
        return new AuthResponse(identityService.accessTokenFor(membership));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(IdentityService.normalizeEmail(request.email()))
                .filter(u -> identityService.passwordMatches(u, request.password()))
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        OrgMembership membership = identityService.requireLandingMembership(user);
        return ResponseEntity.ok(new AuthResponse(identityService.accessTokenFor(membership)));
    }

    public record SwitchOrgRequest(@NotNull UUID orgId) {}

    @PostMapping("/switch-org")
    @PreAuthorize("isAuthenticated()")
    public AuthResponse switchOrg(@Valid @RequestBody SwitchOrgRequest request) {
        var claims = MeController.currentClaims();
        OrgMembership membership = membershipRepository.findById(new OrgMembershipId(claims.userId(), request.orgId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of that org"));
        User user = membership.getUser();
        user.setLastOrgId(request.orgId());
        userRepository.save(user);
        return new AuthResponse(identityService.accessTokenFor(membership));
    }
}
