package com.agentstore.platform.ledger;

import com.agentstore.platform.catalog.Agent;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.verification.DeveloperVerificationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
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
    private final RunRepository runRepository;
    private final DeveloperVerificationRepository developerVerificationRepository;
    private final FeePolicy feePolicy;
    private final LedgerAccounts ledgerAccounts;
    private final BillingModeService billingModeService;

    @PersistenceContext
    private EntityManager entityManager;

    public LedgerService(UsageEventRepository usageEventRepository, LedgerEntryRepository ledgerEntryRepository,
                          WalletRepository walletRepository, LedgerTransactionRepository ledgerTransactionRepository,
                          LedgerPostingRepository ledgerPostingRepository,
                          LedgerAccountBalanceRepository ledgerAccountBalanceRepository, RunRepository runRepository,
                          DeveloperVerificationRepository developerVerificationRepository, FeePolicy feePolicy,
                          @Lazy LedgerAccounts ledgerAccounts, BillingModeService billingModeService) {
        this.usageEventRepository = usageEventRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.walletRepository = walletRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerPostingRepository = ledgerPostingRepository;
        this.ledgerAccountBalanceRepository = ledgerAccountBalanceRepository;
        this.runRepository = runRepository;
        this.developerVerificationRepository = developerVerificationRepository;
        this.feePolicy = feePolicy;
        this.ledgerAccounts = ledgerAccounts;   // lazy: LedgerAccounts itself depends on this service
        this.billingModeService = billingModeService;
    }

    /**
     * Charges a usage event to its buyer, once: writes the {@code ledger_entry} and posts a RUN_CHARGE
     * {@code usage:{usageEventId}} that moves the charge into the developer's payable, the platform fee and any
     * withheld taxes. A prepaid buyer pays from BUYER_WALLET and {@code wallet.balance} is debited; a postpaid buyer's
     * BUYER_RECEIVABLE goes negative by the charge (what it owes) and {@code wallet.balance} is untouched.
     */
    @Transactional
    public LedgerEntry chargeUsageEvent(UUID usageEventId) {
        var existing = ledgerEntryRepository.findByUsageEventId(usageEventId);
        if (existing.isPresent()) {
            return existing.get();
        }

        UsageEvent event = usageEventRepository.findById(usageEventId)
                .orElseThrow(() -> new IllegalArgumentException("UsageEvent not found: " + usageEventId));
        Run run = runRepository.findById(event.getRunId())
                .orElseThrow(() -> new IllegalStateException("Run not found for usage event " + usageEventId));
        Agent agent = run.getAgentVersion().getAgent();
        UUID developerOrgId = agent.getDeveloperOrg().getId();
        BigDecimal charge = event.getAmount().setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal feePercent = feePolicy.feePercent(agent);
        boolean developerHasPan = developerVerificationRepository.findByOrgId(developerOrgId)
                .map(v -> v.getPanEncrypted() != null).orElse(false);
        FeePolicy.Split split = feePolicy.split(charge, feePercent, developerHasPan);

        // Resolve the credit-side accounts before taking the wallet lock.
        List<Posting> lines = new ArrayList<>();
        lines.add(new Posting(ledgerAccounts.orgAccount(developerOrgId, LedgerAccountKind.DEVELOPER_PAYABLE),
                split.developer()));
        lines.add(new Posting(ledgerAccounts.platformAccount(LedgerAccountKind.PLATFORM_REVENUE), split.platformFee()));
        if (split.tds().signum() != 0) {
            lines.add(new Posting(ledgerAccounts.platformAccount(LedgerAccountKind.TAX_TDS_PAYABLE), split.tds()));
        }
        if (split.gstTcs().signum() != 0) {
            lines.add(new Posting(ledgerAccounts.platformAccount(LedgerAccountKind.TAX_TCS_PAYABLE), split.gstTcs()));
        }
        // buyerWallet may post an opening balance against EXTERNAL.
        ledgerAccounts.platformAccount(LedgerAccountKind.EXTERNAL);

        LedgerEntry entry = ledgerEntryRepository.save(new LedgerEntry(null, event.getId(), event.getRunId(),
                event.getOrgId(), event.getAmount(), LedgerEntryType.CHARGE));

        // Lock order: the wallet row first, then (inside post) the balance rows. A postpaid wallet is locked too, so
        // the charge and reservations against the credit limit are serialised. A switch to POSTPAID after this read
        // went through setMode, which creates the row first.
        if (billingModeService.modeFor(event.getOrgId()) == BillingMode.POSTPAID) {
            walletRepository.insertEmptyIfMissing(event.getOrgId());
        }
        Wallet wallet = walletRepository.findByOrgId(event.getOrgId())
                .orElseThrow(() -> new IllegalStateException("No wallet for org " + event.getOrgId()));
        // A wallet read earlier in this transaction is not refreshed by the locking query.
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        // Re-read under the lock: setMode takes it too, so a switch that committed after the read above shows here.
        boolean postpaid = billingModeService.currentModeFor(event.getOrgId()) == BillingMode.POSTPAID;
        // Prepaid: before the wallet balance changes, as first use seeds the account with the current balance.
        lines.add(new Posting(postpaid
                ? ledgerAccounts.orgAccount(event.getOrgId(), LedgerAccountKind.BUYER_RECEIVABLE)
                : ledgerAccounts.buyerWallet(wallet), charge.negate()));
        post(LedgerTransactionType.RUN_CHARGE, "usage:" + event.getId(), run.getId(), event.getOrgId(),
                Map.of("agentId", agent.getId().toString(),
                        "agentVersionId", run.getAgentVersion().getId().toString(),
                        "feePercent", feePercent.toPlainString()),
                lines);

        if (!postpaid) {
            wallet.setBalance(wallet.getBalance().subtract(event.getAmount()));
            wallet.setUpdatedAt(Instant.now());
            walletRepository.save(wallet);
        }

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
        return postWithResult(type, idempotencyKey, runId, orgId, reference, postings).transaction();
    }

    /** What {@link #postWithResult} did: {@code created} is false when the key replayed an existing transaction. */
    public record PostResult(LedgerTransaction transaction, boolean created) {}

    /**
     * {@link #post}, also reporting whether this call created the transaction. Callers that dual-write outside the
     * ledger (such as {@code wallet.balance}) gate that write on {@code created}, so a replay cannot apply it twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PostResult postWithResult(LedgerTransactionType type, String idempotencyKey, UUID runId, UUID orgId,
                                     Map<String, Object> reference, List<Posting> postings) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Map<UUID, BigDecimal> byAccount = validatedTotals(postings);

        var existing = ledgerTransactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new PostResult(existing.get(), false);
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
        return new PostResult(transaction, true);
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
