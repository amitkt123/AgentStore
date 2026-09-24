package com.agentstore.platform.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Holds a run's spend cap against the wallet so concurrent runs cannot overspend (spec WS0 B9). */
@Service
public class WalletReservationService {

    private final WalletRepository walletRepository;

    public WalletReservationService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public void reserve(UUID orgId, BigDecimal amount) {
        Wallet wallet = walletRepository.findByOrgId(orgId)   // PESSIMISTIC_WRITE row lock
                .orElseThrow(() -> new InsufficientFundsException("No wallet for org"));
        BigDecimal available = wallet.getBalance().subtract(wallet.getReserved());
        if (available.compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient wallet balance for spend cap");
        }
        wallet.setReserved(wallet.getReserved().add(amount));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
    }

    @Transactional
    public void release(UUID orgId, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        Wallet wallet = walletRepository.findByOrgId(orgId)
                .orElseThrow(() -> new IllegalStateException("No wallet for org " + orgId));
        wallet.setReserved(wallet.getReserved().subtract(amount).max(BigDecimal.ZERO));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
    }
}
