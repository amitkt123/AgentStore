package com.agentstore.platform.identity;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class UserRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    OrgRepository orgRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    OrgMembershipRepository membershipRepository;

    @Test
    void membershipLinksAUserToAnOrgWithARoleAndTitle() {
        Org org = orgRepository.save(new Org(null, "Acme SMB", OrgType.BUYER));
        User user = userRepository.save(new User(null, "finance-repo@acme.example", "hashed-pw", "Arjun Mehta"));
        membershipRepository.save(new OrgMembership(user, org, UserRole.BUYER_MEMBER, "Accounts manager"));

        var found = userRepository.findByEmailIgnoreCase("Finance-Repo@Acme.example");
        assertThat(found).isPresent();
        assertThat(membershipRepository.findByIdUserIdOrderByCreatedAtAsc(found.get().getId()))
                .singleElement().satisfies(m -> {
                    assertThat(m.getOrg().getName()).isEqualTo("Acme SMB");
                    assertThat(m.getRole()).isEqualTo(UserRole.BUYER_MEMBER);
                    assertThat(m.getTitle()).isEqualTo("Accounts manager");
                });
    }
}
