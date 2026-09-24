package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletReservationServiceTest extends AbstractIntegrationTest {

    @Autowired
    WalletReservationService walletReservationService;
    @Autowired
    WalletRepository walletRepository;

    UUID walletFor(BigDecimal balance) {
        UUID orgId = UUID.randomUUID();
        Wallet wallet = new Wallet(null, orgId, balance, null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return orgId;
    }

    @Test
    void reserveRejectsANegativeAmount() {
        UUID orgId = walletFor(new BigDecimal("1000.00"));

        assertThatThrownBy(() -> walletReservationService.reserve(orgId, new BigDecimal("-1000.00")))
                .isInstanceOf(IllegalArgumentException.class);

        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        assertThat(wallet.getReserved()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void releaseRejectsANegativeAmount() {
        UUID orgId = walletFor(new BigDecimal("1000.00"));
        walletReservationService.reserve(orgId, new BigDecimal("200.00"));

        assertThatThrownBy(() -> walletReservationService.release(orgId, new BigDecimal("-50.00")))
                .isInstanceOf(IllegalArgumentException.class);

        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        assertThat(wallet.getReserved()).isEqualByComparingTo(new BigDecimal("200.00"));
    }
}
