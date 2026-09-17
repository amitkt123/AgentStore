package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import com.agentstore.platform.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WalletControllerTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    JwtService jwtService;
    @Autowired
    OrgRepository orgRepository;
    @Autowired
    WalletRepository walletRepository;

    String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void ownerCanReadTheirWalletBalance() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("41220.00"), new BigDecimal("50000.00"));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo(new BigDecimal("41220.00"));
    }

    @Test
    void cannotReadAnotherOrgsWallet() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo2", OrgType.BUYER));
        Org intruder = orgRepository.save(new Org(null, "IntruderCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("1000.00"), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), intruder.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void walletNotFoundForOrgWithoutWallet() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo3", OrgType.BUYER));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), buyer.getId(), UserRole.BUYER_ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminCanReadAnyOrgWallet() {
        Org buyer = orgRepository.save(new Org(null, "BuyerCo4", OrgType.BUYER));
        Org admin = orgRepository.save(new Org(null, "AdminCo", OrgType.BUYER));
        Wallet wallet = new Wallet(null, buyer.getId(), new BigDecimal("25000.00"), new BigDecimal("100000.00"));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issue(UUID.randomUUID(), admin.getId(), UserRole.ADMIN));

        var response = rest.exchange(url("/api/v1/orgs/" + buyer.getId() + "/wallet"), HttpMethod.GET,
                new HttpEntity<>(headers), WalletResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().balance()).isEqualByComparingTo(new BigDecimal("25000.00"));
        assertThat(response.getBody().cap()).isEqualByComparingTo(new BigDecimal("100000.00"));
    }
}
