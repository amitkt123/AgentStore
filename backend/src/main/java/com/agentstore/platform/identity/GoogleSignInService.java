package com.agentstore.platform.identity;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class GoogleSignInService {

    static final String PROVIDER = "GOOGLE";

    private final GoogleIdTokenVerifier verifier;
    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final IdentityService identityService;

    public GoogleSignInService(GoogleIdTokenVerifier verifier, UserRepository userRepository,
                               UserIdentityRepository identityRepository, IdentityService identityService) {
        this.verifier = verifier;
        this.userRepository = userRepository;
        this.identityRepository = identityRepository;
        this.identityService = identityService;
    }

    /** Raised as 409/422 with a stable {@code code} the web app can act on. */
    public static class SignInProblem extends RuntimeException {
        public final HttpStatus status;
        public final String code;

        SignInProblem(HttpStatus status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    @Transactional
    public OrgMembership signIn(String idToken, SignupSide side, String orgName) {
        GoogleIdentity google = verifier.verify(idToken)
                .filter(GoogleIdentity::emailVerified)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google sign-in failed"));

        User user = identityRepository.findByProviderAndSubject(PROVIDER, google.subject())
                .flatMap(link -> userRepository.findById(link.getUserId()))
                .orElse(null);

        if (user == null) {
            String email = IdentityService.normalizeEmail(google.email());
            User existing = userRepository.findByEmailIgnoreCase(email).orElse(null);
            if (existing != null) {
                if (existing.getEmailVerifiedAt() == null) {
                    throw new SignInProblem(HttpStatus.CONFLICT, "ACCOUNT_EXISTS_UNVERIFIED",
                            "Sign in with your password and confirm your email first");
                }
                user = existing;
            } else {
                if (side == null || orgName == null || orgName.isBlank()) {
                    throw new SignInProblem(HttpStatus.UNPROCESSABLE_ENTITY, "SIGNUP_DETAILS_REQUIRED",
                            "Pick what you're here to do and name your workspace");
                }
                User created = new User(null, email, null, google.name() != null ? google.name() : email);
                created.setEmailVerifiedAt(Instant.now());
                user = userRepository.save(created);
                identityRepository.save(new UserIdentity(PROVIDER, google.subject(), user.getId()));
                UserRole role = identityService.adminAwareRole(side, email);
                return identityService.addOrg(user, side, orgName, role);
            }
            identityRepository.save(new UserIdentity(PROVIDER, google.subject(), user.getId()));
        }

        return identityService.requireLandingMembership(user);
    }
}
