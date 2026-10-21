package com.agentstore.platform.ledger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Finds ledger accounts. Platform accounts (V45) and each org's DEVELOPER_PAYABLE or BUYER_RECEIVABLE (V46, on org
 * insert) exist up front, so charges and finishes only look them up while holding run or wallet locks. A missing one
 * is still created, with its zero balance row, in the caller's transaction ({@code on conflict do nothing}), so no
 * second connection is ever needed under a lock.
 */
@Component
public class LedgerAccounts {

    private final LedgerAccountRepository accountRepository;
    private final LedgerAccountBalanceRepository balanceRepository;
    private final LedgerService ledgerService;

    @PersistenceContext
    private EntityManager entityManager;

    public LedgerAccounts(LedgerAccountRepository accountRepository, LedgerAccountBalanceRepository balanceRepository,
                          LedgerService ledgerService) {
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.ledgerService = ledgerService;
    }

    /** A buyer or developer org's account. The buyer wallet goes through {@link #buyerWallet} instead. */
    @Transactional
    public UUID orgAccount(UUID orgId, LedgerAccountKind kind) {
        Objects.requireNonNull(orgId, "orgId");
        if (kind.isPlatform()) {
            throw new IllegalArgumentException(kind + " is a platform account; use platformAccount");
        }
        if (kind == LedgerAccountKind.BUYER_WALLET) {
            throw new IllegalArgumentException("BUYER_WALLET needs its opening balance; use buyerWallet");
        }
        return find(orgId, kind).orElseGet(() -> createIfMissing(orgId, kind));
    }

    /** A platform account. V45 creates them all, so this is normally a plain lookup and takes no lock. */
    @Transactional
    public UUID platformAccount(LedgerAccountKind kind) {
        if (!kind.isPlatform()) {
            throw new IllegalArgumentException(kind + " is an org account; use orgAccount");
        }
        return find(null, kind).orElseGet(() -> createIfMissing(null, kind));
    }

    /**
     * The org's BUYER_WALLET account. On first use it is created in the caller's transaction and, when the wallet
     * already holds money, seeded with an ADJUSTMENT {@code opening:{orgId}} (BUYER_WALLET +balance, EXTERNAL
     * -balance), so the account and its opening balance commit or roll back together. The caller's lock on the
     * wallet row serialises creation per org, so no concurrent insert can race this one.
     * <p>
     * Call it before changing {@code lockedWallet}'s balance in the same transaction: the wallet's current balance
     * is taken as the opening amount, so a balance already adjusted for this transaction's charge would be wrong.
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

    /**
     * Inserts the account unless it exists, then reads whichever row holds the unique index. A concurrent creator
     * inserts its balance row in the same transaction as its account, so only our own insert needs one.
     */
    private UUID createIfMissing(UUID orgId, LedgerAccountKind kind) {
        int inserted = orgId == null
                ? accountRepository.insertPlatformAccountIfMissing(kind.name())
                : accountRepository.insertOrgAccountIfMissing(orgId, kind.name());
        UUID account = find(orgId, kind)
                .orElseThrow(() -> new IllegalStateException("Ledger account " + kind + " missing after insert"));
        if (inserted == 1) {
            accountRepository.insertBalanceIfMissing(account);
        }
        return account;
    }

    private UUID create(UUID orgId, LedgerAccountKind kind) {
        LedgerAccount account = accountRepository.saveAndFlush(new LedgerAccount(orgId, kind));
        balanceRepository.saveAndFlush(new LedgerAccountBalance(account.getId()));
        return account.getId();
    }
}
