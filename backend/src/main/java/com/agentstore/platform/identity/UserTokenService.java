package com.agentstore.platform.identity;

import com.agentstore.platform.common.EmailMessage;
import com.agentstore.platform.common.EmailSender;
import com.agentstore.platform.common.TokenHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

@Service
public class UserTokenService {

    static final Duration VERIFY_TTL = Duration.ofHours(24);
    static final Duration RESET_TTL = Duration.ofHours(1);

    private final UserTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final IdentityService identityService;
    private final EmailSender emailSender;
    private final String webBaseUrl;

    public UserTokenService(UserTokenRepository tokenRepository, UserRepository userRepository,
                            RefreshTokenService refreshTokenService, IdentityService identityService,
                            EmailSender emailSender, @Value("${agentstore.web.base-url}") String webBaseUrl) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.refreshTokenService = refreshTokenService;
        this.identityService = identityService;
        this.emailSender = emailSender;
        this.webBaseUrl = webBaseUrl;
    }

    @Transactional
    public void sendVerification(User user) {
        if (user.getEmailVerifiedAt() != null) return;
        String raw = issue(user, UserTokenPurpose.EMAIL_VERIFY, VERIFY_TTL);
        emailSender.send(new EmailMessage(user.getEmail(), "Confirm your email for Karya",
                "Confirm this address to finish setting up Karya: " + webBaseUrl + "/verify-email/" + raw
                        + "\n\nThe link expires in 24 hours."));
    }

    @Transactional
    public void verifyEmail(String raw) {
        UserToken token = consume(raw, UserTokenPurpose.EMAIL_VERIFY);
        User user = userRepository.findById(token.getUserId()).orElseThrow(UserTokenService::invalid);
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
        }
    }

    /** Always silent: the caller cannot tell whether the email has an account. */
    @Transactional
    public void sendPasswordReset(String email) {
        userRepository.findByEmailIgnoreCase(IdentityService.normalizeEmail(email)).ifPresent(user -> {
            String raw = issue(user, UserTokenPurpose.PASSWORD_RESET, RESET_TTL);
            emailSender.send(new EmailMessage(user.getEmail(), "Reset your Karya password",
                    "Someone asked to reset the password for this Karya account. If it was you: "
                            + webBaseUrl + "/reset-password/" + raw
                            + "\n\nThe link works once and expires in 1 hour. If it wasn't you, ignore this email."));
        });
    }

    @Transactional
    public void resetPassword(String raw, String newPassword) {
        UserToken token = consume(raw, UserTokenPurpose.PASSWORD_RESET);
        User user = userRepository.findById(token.getUserId()).orElseThrow(UserTokenService::invalid);
        user.setPasswordHash(identityService.hashPassword(newPassword));
        // Receiving the reset link proves the inbox, so the address counts as verified too.
        if (user.getEmailVerifiedAt() == null) user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
        refreshTokenService.revokeAllFor(user.getId());
    }

    private String issue(User user, UserTokenPurpose purpose, Duration ttl) {
        Instant now = Instant.now();
        tokenRepository.retireUnused(user.getId(), purpose, now);
        String raw = TokenHasher.newToken();
        tokenRepository.save(new UserToken(user.getId(), purpose, TokenHasher.sha256Hex(raw), now.plus(ttl)));
        return raw;
    }

    private UserToken consume(String raw, UserTokenPurpose purpose) {
        Instant now = Instant.now();
        UserToken token = tokenRepository.findByTokenHashAndPurpose(TokenHasher.sha256Hex(raw), purpose)
                .filter(t -> t.getUsedAt() == null && t.getExpiresAt().isAfter(now))
                .orElseThrow(UserTokenService::invalid);
        token.setUsedAt(now);
        tokenRepository.save(token);
        return token;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "This link is invalid or has expired");
    }
}
