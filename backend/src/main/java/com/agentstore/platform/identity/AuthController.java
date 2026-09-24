package com.agentstore.platform.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;
    private final RefreshTokenService refreshTokenService;
    private final UserTokenService userTokenService;
    private final GoogleSignInService googleSignInService;
    private final GoogleIdTokenVerifier googleVerifier;

    public AuthController(UserRepository userRepository, OrgMembershipRepository membershipRepository,
                          IdentityService identityService, RefreshTokenService refreshTokenService,
                          UserTokenService userTokenService, GoogleSignInService googleSignInService,
                          GoogleIdTokenVerifier googleVerifier) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
        this.refreshTokenService = refreshTokenService;
        this.userTokenService = userTokenService;
        this.googleSignInService = googleSignInService;
        this.googleVerifier = googleVerifier;
    }

    /** Issues an access token, and when {@code remember} is set, a new refresh token family alongside it. */
    private AuthResponse authResponse(OrgMembership membership, boolean remember) {
        String access = identityService.accessTokenFor(membership);
        String refresh = remember ? refreshTokenService.issue(membership.getId().getUserId()) : null;
        return new AuthResponse(access, refresh);
    }

    @PostMapping("/signup")
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {
        OrgMembership membership = identityService.signup(request);
        try {
            userTokenService.sendVerification(membership.getUser());
        } catch (Exception e) {
            // The account is already created; the user can request another link via /auth/email/resend.
            log.warn("Failed to send verification email to user {}", membership.getUser().getId(), e);
        }
        return AuthResponse.access(identityService.accessTokenFor(membership));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(IdentityService.normalizeEmail(request.email()))
                .filter(u -> identityService.passwordMatches(u, request.password()))
                .orElse(null);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            OrgMembership membership = identityService.landOrCreateWorkspace(user, request.side(), request.orgName());
            return ResponseEntity.ok(authResponse(membership, request.remember()));
        } catch (SignInProblem problem) {
            return ResponseEntity.status(problem.status)
                    .body(Map.of("code", problem.code, "message", problem.getMessage()));
        }
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
        return AuthResponse.access(identityService.accessTokenFor(membership));
    }

    public record RefreshRequest(@NotBlank String refreshToken) {}

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        var rotation = refreshTokenService.rotate(request.refreshToken());
        User user = userRepository.findById(rotation.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session expired"));
        OrgMembership membership = identityService.requireLandingMembership(user);
        return new AuthResponse(identityService.accessTokenFor(membership), rotation.newRawToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        refreshTokenService.revokeFamilyOf(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    public record GoogleSignInRequest(@NotBlank String idToken, SignupSide side, String orgName, boolean remember) {}

    @PostMapping("/google")
    public ResponseEntity<?> google(@Valid @RequestBody GoogleSignInRequest request) {
        if (!googleVerifier.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        try {
            OrgMembership membership = googleSignInService.signIn(request.idToken(), request.side(), request.orgName());
            return ResponseEntity.ok(authResponse(membership, request.remember()));
        } catch (SignInProblem problem) {
            return ResponseEntity.status(problem.status)
                    .body(Map.of("code", problem.code, "message", problem.getMessage()));
        }
    }

    public record TokenRequest(@NotBlank String token) {}

    public record ForgotPasswordRequest(@NotBlank @Email String email) {}

    public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 200) String password) {}

    @PostMapping("/email/verify")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody TokenRequest request) {
        userTokenService.verifyEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email/resend")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> resendVerification() {
        userRepository.findById(MeController.currentClaims().userId()).ifPresent(userTokenService::sendVerification);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        userTokenService.sendPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        userTokenService.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }
}
