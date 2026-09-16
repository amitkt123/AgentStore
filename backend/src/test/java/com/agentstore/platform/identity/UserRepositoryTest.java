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

    @Test
    void savesAndFindsUserByEmail() {
        Org org = orgRepository.save(new Org(null, "Acme SMB", OrgType.BUYER));
        User user = userRepository.save(
                new User(null, org, "finance@acme.example", "hashed-pw", UserRole.BUYER_ADMIN));

        var found = userRepository.findByEmail("finance@acme.example");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(user.getId());
        assertThat(found.get().getOrg().getName()).isEqualTo("Acme SMB");
    }
}
