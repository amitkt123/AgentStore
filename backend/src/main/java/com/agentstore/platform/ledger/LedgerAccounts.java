package com.agentstore.platform.ledger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Finds ledger accounts, creating each (with its zero balance row) the first time it is needed. */
@Component
public class LedgerAccounts {

    private final LedgerAccountRepository accountRepository;
    private final LedgerAccountBalanceRepository balanceRepository;
    private final LedgerService ledgerService;
    private final TransactionTemplate requiresNew;

    @PersistenceContext
    private EntityManager entityManager;

    public LedgerAccounts(LedgerAccountRepository accountRepository, LedgerAccountBalanceRepository balanceRepository,
                          LedgerService ledgerService, PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.ledgerService = ledgerService;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A buyer or developer org's account. The buyer wallet goes through {@link #buyerWallet} instead. */
    public UUID orgAccount(UUID orgId, LedgerAccountKind kind) {
        Objects.requireNonNull(orgId, "orgId");
        if (kind.isPlatform()) {
            throw new IllegalArgumentException(kind + " is a platform account; use platformAccount");
        }
        if (kind == LedgerAccountKind.BUYER_WALLET) {
            throw new IllegalArgumentException("BUYER_WALLET needs its opening balance; use buyerWallet");
        }
        return find(orgId, kind).orElseGet(() -> createOrReread(orgId, kind));
    }

    public UUID platformAccount(LedgerAccountKind kind) {
        if (!kind.isPlatform()) {
            throw new IllegalArgumentException(kind + " is an org account; use orgAccount");
        }
        return find(null, kind).orElseGet(() -> createOrReread(null, kind));
    }

    /**
     * The org's BUYER_WALLET account. On first use it is created in the caller's transaction and, when the wallet
     * already holds money, seeded with an ADJUSTMENT {@code opening:{orgId}} (BUYER_WALLET +balance, EXTERNAL
     * -balance), so the account and its opening balance commit or roll back together. The caller's lock on the
     * wallet row serialises creation per org, so no concurrent insert can race this one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID buyerWallet(Wallet lockedWallet) {
        if (!entityManager.contains(lockedWallet)
                || entityManager.getLockMode(lockedWallet) != LockModeType.PESSIMISTIC_WRITE) {
            throw new IllegalStateException("buyerWallet needs the wallet locked in the current transaction");
        }
        UUID orgId = lockedWallet.getOrgId();
        Optional<UUID> existing = find(orgId, LedgerAccountKind.BUYER_WALLET);
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID account = create(orgId, LedgerAccountKind.BUYER_WALLET);
        BigDecimal balance = lockedWallet.getBalance();
        if (balance != null && balance.signum() != 0) {
            ledgerService.post(LedgerTransactionType.ADJUSTMENT, "opening:" + orgId, null, orgId,
                    Map.of("reason", "opening balance"),
                    List.of(new Posting(account, balance),
                            new Posting(platformAccount(LedgerAccountKind.EXTERNAL), balance.negate())));
        }
        return account;
    }

    private Optional<UUID> find(UUID orgId, LedgerAccountKind kind) {
        Optional<LedgerAccount> account = orgId == null
                ? accountRepository.findByOrgIdIsNullAndKind(kind)
                : accountRepository.findByOrgIdAndKind(orgId, kind);
        return account.map(LedgerAccount::getId);
    }

    /** Creates in its own transaction; a concurrent creator that wins the unique index makes us re-read theirs. */
    private UUID createOrReread(UUID orgId, LedgerAccountKind kind) {
        try {
            return requiresNew.execute(status -> create(orgId, kind));
        } catch (DataIntegrityViolationException e) {
            return find(orgId, kind).orElseThrow(() -> e);
        }
    }

    private UUID create(UUID orgId, LedgerAccountKind kind) {
        LedgerAccount account = accountRepository.saveAndFlush(new LedgerAccount(orgId, kind));
        balanceRepository.saveAndFlush(new LedgerAccountBalance(account.getId()));
        return account.getId();
    }
}
