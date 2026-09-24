package com.agentstore.platform.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;

/** Creates real users and memberships directly, for tests that need several people in one org. */
@Component
public class IdentityFixtures {

    private final OrgRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgMembershipRepository membershipRepository;
    private final IdentityService identityService;

    public IdentityFixtures(OrgRepository orgRepository, UserRepository userRepository,
                            OrgMembershipRepository membershipRepository, IdentityService identityService) {
        this.orgRepository = orgRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.identityService = identityService;
    }

    public OrgMembership buyerAdmin(String email) {
        Org org = orgRepository.save(new Org(null, "Org " + UUID.randomUUID(), OrgType.BUYER));
        return member(org, email, UserRole.BUYER_ADMIN, "Finance lead");
    }

    public OrgMembership member(Org org, String email, UserRole role, String title) {
        User user = userRepository.findByEmailIgnoreCase(email).orElseGet(() -> userRepository.save(
                new User(null, IdentityService.normalizeEmail(email), identityService.hashPassword("s3cret-pw"),
                        "Person " + email)));
        return membershipRepository.save(new OrgMembership(user, org, role, title));
    }

    public String tokenFor(OrgMembership membership) {
        return identityService.accessTokenFor(membership);
    }
}
