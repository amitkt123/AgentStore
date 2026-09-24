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
        UserRole role = adminAwareRole(request.side(), email);
        return addOrg(user, request.side(), request.orgName(), role);
    }

    /**
     * The role a brand-new org on this side grants the given email: {@code ADMIN} for a developer-side
     * signup from an address listed in {@code agentstore.identity.admin-emails}, otherwise the side's
     * ordinary admin role. The one place signup (and later Google new-user provisioning) computes this,
     * so {@code ADMIN} is never granted anywhere else.
     */
    public UserRole adminAwareRole(SignupSide side, String email) {
        return side == SignupSide.DEVELOPER && adminEmails.contains(normalizeEmail(email))
                ? UserRole.ADMIN
                : OrgRoles.adminRoleOf(side.orgType());
    }

    /** Creates an org of the given side with this user as its first member, and makes it the landing org. */
    @Transactional
    public OrgMembership addOrg(User user, SignupSide side, String orgName, UserRole role) {
        Org org = orgRepository.save(new Org(null, orgName.trim(), side.orgType()));
        OrgMembership membership = membershipRepository.save(new OrgMembership(user, org, role, null));
        user.setLastOrgId(org.getId());
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
