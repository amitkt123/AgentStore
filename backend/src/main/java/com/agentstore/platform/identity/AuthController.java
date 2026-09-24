package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserRepository userRepository;
    private final IdentityService identityService;

    public AuthController(UserRepository userRepository, IdentityService identityService) {
        this.userRepository = userRepository;
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
}
