package com.agentstore.platform.ledger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class LedgerService {

    private final UsageEventRepository usageEventRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final WalletRepository walletRepository;
    private final LedgerTransactionRepository ledgerTransactionRepository;
    private final LedgerPostingRepository ledgerPostingRepository;
    private final LedgerAccountBalanceRepository ledgerAccountBalanceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public LedgerService(UsageEventRepository usageEventRepository, LedgerEntryRepository ledgerEntryRepository,
                          WalletRepository walletRepository, LedgerTransactionRepository ledgerTransactionRepository,
                          LedgerPostingRepository ledgerPostingRepository,
                          LedgerAccountBalanceRepository ledgerAccountBalanceRepository) {
        this.usageEventRepository = usageEventRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletRepository = walletRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerPostingRepository = ledgerPostingRepository;
        this.ledgerAccountBalanceRepository = ledgerAccountBalanceRepository;
    }

    @Transactional
    public LedgerEntry chargeUsageEvent(UUID usageEventId) {
        var existing = ledgerEntryRepository.findByUsageEventId(usageEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UsageEvent event = usageEventRepository.findById(usageEventId)
                .orElseThrow(() -> new IllegalArgumentException("UsageEvent not found: " + usageEventId));

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(null, event.getId(), event.getRunId(),
                event.getOrgId(), event.getAmount(), LedgerEntryType.CHARGE));

        Wallet wallet = walletRepository.findByOrgId(event.getOrgId())
                .orElseThrow(() -> new IllegalStateException("No wallet for org " + event.getOrgId()));
        wallet.setBalance(wallet.getBalance().subtract(event.getAmount()));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);

        event.setProcessed(true);
        usageEventRepository.save(event);

        return entry;
    }

    /**
     * Records one balanced transaction. The only code that inserts postings or changes account balances.
     * Replaying an existing idempotency key returns that transaction unchanged and posts nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(LedgerTransactionType type, String idempotencyKey, UUID runId, UUID orgId,
                                  Map<String, Object> reference, List<Posting> postings) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Map<UUID, BigDecimal> byAccount = validatedTotals(postings);

        var existing = ledgerTransactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setType(type);
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setRunId(runId);
        transaction.setOrgId(orgId);
        transaction.setReference(reference);
        transaction = ledgerTransactionRepository.saveAndFlush(transaction);

        // Lock order: ascending account_id (byAccount iterates in Postgres uuid order).
        // A balance already managed in this persistence context (e.g. read by balanceOf earlier) is returned as-is
        // by the locking query, so refresh it under the lock. Pending balance changes were flushed by saveAndFlush.
        Map<UUID, LedgerAccountBalance> locked = new LinkedHashMap<>();
        for (UUID accountId : byAccount.keySet()) {
            LedgerAccountBalance balance = ledgerAccountBalanceRepository.findForUpdate(accountId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown ledger account: " + accountId));
            entityManager.refresh(balance);
            locked.put(accountId, balance);
        }

        for (Posting posting : postings) {
            ledgerPostingRepository.save(new LedgerPosting(transaction.getId(), posting.accountId(),
                    posting.amount().setScale(2, RoundingMode.UNNECESSARY)));
        }
        for (var entry : byAccount.entrySet()) {
            LedgerAccountBalance balance = locked.get(entry.getKey());
            balance.setBalance(balance.getBalance().add(entry.getValue()).setScale(2, RoundingMode.UNNECESSARY));
            balance.setVersion(balance.getVersion() + 1);
        }
        return transaction;
    }

    @Transactional(readOnly = true)
    public BigDecimal balanceOf(UUID accountId) {
        return ledgerAccountBalanceRepository.findById(accountId)
                .map(LedgerAccountBalance::getBalance)
                .orElseThrow(() -> new IllegalArgumentException("Unknown ledger account: " + accountId));
    }

    /** Per-account totals, sorted ascending as Postgres orders uuids; rejects anything that is not a balanced, whole-paisa set. */
    private static Map<UUID, BigDecimal> validatedTotals(List<Posting> postings) {
        if (postings == null || postings.isEmpty()) {
            throw new IllegalArgumentException("A ledger transaction needs at least one posting");
        }
        // Lowercase hex string order equals Postgres' unsigned byte order for uuid; UUID.compareTo is signed.
        Map<UUID, BigDecimal> byAccount = new TreeMap<>(Comparator.comparing(UUID::toString));
        BigDecimal sum = BigDecimal.ZERO;
        for (Posting posting : postings) {
            if (posting == null || posting.accountId() == null || posting.amount() == null) {
                throw new IllegalArgumentException("Posting needs an account and an amount: " + posting);
            }
            if (posting.amount().stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("Posting amount has more than 2 decimal places: " + posting.amount());
            }
            byAccount.merge(posting.accountId(), posting.amount(), BigDecimal::add);
            sum = sum.add(posting.amount());
        }
        if (sum.signum() != 0) {
            throw new IllegalArgumentException("Postings do not sum to zero: " + sum);
        }
        return byAccount;
    }
}
