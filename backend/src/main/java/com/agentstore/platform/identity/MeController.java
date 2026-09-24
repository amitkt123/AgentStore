package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;

    public MeController(UserRepository userRepository, OrgMembershipRepository membershipRepository,
                        IdentityService identityService) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
    }

    public static JwtService.Claims currentClaims() {
        return (JwtService.Claims) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public MeResponse me() {
        var claims = currentClaims();
        User user = currentUser(claims);
        var memberships = membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .map(MeResponse.Membership::from).toList();
        return new MeResponse(user.getId(), user.getEmail(), user.getFullName(), user.getEmailVerifiedAt() != null,
                claims.orgId(), memberships);
    }

    public record AddOrgRequest(@NotNull SignupSide side, @NotBlank @Size(max = 255) String orgName) {}

    @PostMapping("/orgs")
    @PreAuthorize("isAuthenticated()")
    public AuthResponse addOrg(@Valid @RequestBody AddOrgRequest request) {
        User user = currentUser(currentClaims());
        UserRole role = OrgRoles.adminRoleOf(request.side().orgType());
        return new AuthResponse(identityService.accessTokenFor(
                identityService.addOrg(user, request.side(), request.orgName(), role)));
    }

    private User currentUser(JwtService.Claims claims) {
        return userRepository.findById(claims.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }
}
