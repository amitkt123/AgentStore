package com.agentstore.platform.execution;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.catalog.AgentRepository;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecretServiceTest extends AbstractIntegrationTest {

    @Autowired
    SecretService secretService;
    @Autowired
    ConnectionRepository connectionRepository;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    AgentRepository agentRepository;
    @Autowired
    SecretRepository secretRepository;

    @Test
    void storesEncryptedAndRevealsPlaintext() {
        Org developer = orgRepository.save(new Org(null, "DevCo", OrgType.DEVELOPER));
        Org buyer = orgRepository.save(new Org(null, "Acme", OrgType.BUYER));
        Agent agent = agentRepository.save(new Agent(null, developer, "Bank Reconciliation", "reconciliation", "d"));
        Connection connection = connectionRepository.save(new Connection(null, buyer, agent, List.of("bank_statement"),
                new BigDecimal("500.00"), ConnectionStatus.ACTIVE));

        secretService.store(connection.getId(), "BANK_API_KEY", "plaintext-value-123");

        Secret stored = secretRepository.findByConnectionIdAndName(connection.getId(), "BANK_API_KEY").orElseThrow();
        assertThat(new String(stored.getEncryptedValue())).doesNotContain("plaintext-value-123");

        assertThat(secretService.reveal(connection.getId(), "BANK_API_KEY")).isEqualTo("plaintext-value-123");
    }
}
