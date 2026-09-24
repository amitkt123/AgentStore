package com.agentstore.platform.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Signup, which membership a login lands in, and adding orgs to an existing person. */
@Service
public class IdentityService {

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final JwtService jwtService;
    private final Set<String> adminEmails;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public IdentityService(OrgRepository orgRepository, UserRepository userRepository,
                           OrgMembershipRepository membershipRepository, JwtService jwtService,
                           @Value("${agentstore.identity.admin-emails:}") List<String> adminEmails) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.jwtService = jwtService;
        this.adminEmails = adminEmails.stream().filter(e -> !e.isBlank()).map(IdentityService::normalizeEmail)
                .collect(Collectors.toSet());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public OrgMembership signup(SignupRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        User user;
        try {
            user = userRepository.saveAndFlush(new User(null, email, hashPassword(request.password()),
                    request.fullName().trim()));
        } catch (DataIntegrityViolationException e) {
            // Two signups for the same email raced past the exists check.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An account with this email already exists");
        }
        // A password signup never grants ADMIN before the email is verified, even for a listed address
        // (I1): it gets the side's ordinary role, and promoteToAdminIfListed upgrades it once a
        // completed password reset proves control of the inbox.
        UserRole role = OrgRoles.adminRoleOf(request.side().orgType());
        return addOrg(user, request.side(), request.orgName(), role);
    }

    /**
     * The role a brand-new Google-signup org on this side grants the given email: {@code ADMIN} for a
     * developer-side signup from an address listed in {@code agentstore.identity.admin-emails}, otherwise
     * the side's ordinary admin role. Safe to grant immediately here because Google sign-in already
     * requires a verified email before a new account is created. Password signup does not use this; see
     * {@link #promoteToAdminIfListed}.
     */
    UserRole adminAwareRole(SignupSide side, String email) {
        return side == SignupSide.DEVELOPER && adminEmails.contains(normalizeEmail(email))
                ? UserRole.ADMIN
                : OrgRoles.adminRoleOf(side.orgType());
    }

    /**
     * Upgrades a user's own signup-org membership ({@link User#getSignupOrgId()}) to platform
     * {@code ADMIN}, if that user's email is listed in {@code agentstore.identity.admin-emails}. Callers
     * must only invoke this once a completed password reset has proved control of the inbox and replaced
     * whatever password an impostor may have set (verifying the email alone is not enough: someone else
     * could have signed up with this address first). A no-op for an unlisted email, a user with no
     * recorded signup org (for example one created by accepting an invitation, who never created an org
     * of their own), a signup-org membership that no longer exists, an already-ADMIN membership, or a
     * signup org that isn't a developer org (matching {@link #adminAwareRole}, which never grants ADMIN
     * on the buyer side). Idempotent.
     */
    @Transactional
    public void promoteToAdminIfListed(User user) {
        if (!adminEmails.contains(normalizeEmail(user.getEmail()))) return;
        if (user.getSignupOrgId() == null) return;
        membershipRepository.findById(new OrgMembershipId(user.getId(), user.getSignupOrgId()))
                .filter(m -> m.getOrg().getType() == OrgType.DEVELOPER)
                .filter(m -> m.getRole() != UserRole.ADMIN)
                .ifPresent(m -> {
                    m.setRole(UserRole.ADMIN);
                    membershipRepository.save(m);
                });
    }

    /**
     * Creates an org of the given side with this user as its first member, and makes it the landing org.
     * Also records it as the user's signup org, the first time this runs for them (never overwritten by
     * a later {@code /me/orgs} call), so {@link #promoteToAdminIfListed} can find it again.
     */
    @Transactional
    public OrgMembership addOrg(User user, SignupSide side, String orgName, UserRole role) {
        Org org = orgRepository.save(new Org(null, orgName.trim(), side.orgType()));
        OrgMembership membership = membershipRepository.save(new OrgMembership(user, org, role, null));
        user.setLastOrgId(org.getId());
        if (user.getSignupOrgId() == null) user.setSignupOrgId(org.getId());
        userRepository.save(user);
        return membership;
    }

    /** The last org the user worked in if they are still a member, otherwise their oldest membership. */
    public Optional<OrgMembership> landingMembership(User user) {
        List<OrgMembership> memberships = membershipRepository.findByIdUserIdOrderByCreatedAtAsc(user.getId());
        return memberships.stream()
                .filter(m -> m.getId().getOrgId().equals(user.getLastOrgId()))
                .findFirst()
                .or(() -> memberships.stream().findFirst());
    }

    /** The membership a login lands in, or 403 if the user belongs to no workspace. */
    public OrgMembership requireLandingMembership(User user) {
        return landingMembership(user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "You are not a member of any workspace"));
    }

    /**
     * The membership a login (password or Google) lands in, for a user whose credentials already checked
     * out. When the user still belongs to a workspace, that membership wins and {@code side}/{@code
     * orgName} are ignored, even if sent. When they don't — for example every membership they had was
     * removed — {@code side} and {@code orgName} (both required, non-blank) create a brand-new workspace
     * with them as its only member, using the side's ordinary admin role. This intentionally never calls
     * {@link #adminAwareRole}: recreating a workspace this way must never grant platform {@code ADMIN},
     * even to a listed email (only a completed password reset can do that; see
     * {@link #promoteToAdminIfListed}). Missing or blank fields raise {@code NO_WORKSPACE} (422) instead of
     * the 403 {@link #requireLandingMembership} throws, so the caller knows to prompt for a workspace name
     * rather than send the user back to a login screen they can never get past.
     */
    @Transactional
    public OrgMembership landOrCreateWorkspace(User user, SignupSide side, String orgName) {
        return landingMembership(user)
                .orElseGet(() -> {
                    if (side == null || orgName == null || orgName.isBlank()) {
                        throw new SignInProblem(HttpStatus.UNPROCESSABLE_ENTITY, "NO_WORKSPACE",
                                "You're not a member of any workspace any more. Create one to continue.");
                    }
                    return addOrg(user, side, orgName, OrgRoles.adminRoleOf(side.orgType()));
                });
    }

    public String accessTokenFor(OrgMembership membership) {
        return jwtService.issue(membership.getId().getUserId(), membership.getId().getOrgId(), membership.getRole());
    }

    public boolean passwordMatches(User user, String rawPassword) {
        return user.getPasswordHash() != null && passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    public String hashPassword(String raw) {
        return passwordEncoder.encode(raw);
    }
}
