package com.agentstore.platform.ledger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Holds a run's spend cap against the wallet so concurrent runs cannot overspend (spec WS0 B9). Prepaid orgs hold it
 * against the wallet balance; postpaid orgs against their credit limit, less what they already owe.
 */
@Service
public class WalletReservationService {

    private final WalletRepository walletRepository;
    private final BillingModeService billingModeService;
    private final LedgerAccountRepository accountRepository;
    private final LedgerAccountBalanceRepository balanceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public WalletReservationService(WalletRepository walletRepository, BillingModeService billingModeService,
                                    LedgerAccountRepository accountRepository,
                                    LedgerAccountBalanceRepository balanceRepository) {
        this.walletRepository = walletRepository;
        this.billingModeService = billingModeService;
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
    }

    @Transactional
    public void reserve(UUID orgId, BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Reservation amount must not be negative: " + amount);
        }
        Wallet wallet;
        if (billingModeService.modeFor(orgId) == BillingMode.POSTPAID) {
            walletRepository.insertEmptyIfMissing(orgId);
            wallet = lock(orgId).orElseThrow();
            // Charges to the receivable take this wallet lock first, so what is owed cannot move under us.
            BigDecimal committed = owed(orgId).add(wallet.getReserved()).add(amount);
            if (committed.compareTo(billingModeService.creditLimit(orgId)) > 0) {
                throw new InsufficientFundsException("Credit limit reached");
            }
        } else {
            wallet = lock(orgId).orElseThrow(() -> new InsufficientFundsException("No wallet for org"));
            BigDecimal available = wallet.getBalance().subtract(wallet.getReserved());
            if (available.compareTo(amount) < 0) {
                throw new InsufficientFundsException("Insufficient wallet balance for spend cap");
            }
        }
        wallet.setReserved(wallet.getReserved().add(amount));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
    }

    @Transactional
    public void release(UUID orgId, BigDecimal amount) {
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Release amount must not be negative: " + amount);
        }
        if (amount.signum() == 0) {
            return;
        }
        Wallet wallet = lock(orgId).orElseThrow(() -> new IllegalStateException("No wallet for org " + orgId));
        wallet.setReserved(wallet.getReserved().subtract(amount).max(BigDecimal.ZERO));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
    }

    /** Locks the wallet row and reloads it, in case this transaction had already read it before the lock. */
    private Optional<Wallet> lock(UUID orgId) {
        Optional<Wallet> wallet = walletRepository.findByOrgId(orgId);
        wallet.ifPresent(w -> entityManager.refresh(w, LockModeType.PESSIMISTIC_WRITE));
        return wallet;
    }

    /** What a postpaid org owes: the negated BUYER_RECEIVABLE balance, or 0 before its first charge. */
    private BigDecimal owed(UUID orgId) {
        return accountRepository.findByOrgIdAndKind(orgId, LedgerAccountKind.BUYER_RECEIVABLE)
                .flatMap(account -> balanceRepository.findById(account.getId()))
                .map(balance -> {
                    entityManager.refresh(balance);
                    return balance.getBalance().negate();
                })
                .orElse(BigDecimal.ZERO);
    }
}
