package com.agentstore.platform.ledger;

import com.agentstore.platform.data.Caller;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminMoneyController {

    /**
     * Platform balances plus three ledger checks: {@code totalsCheck} (all balances sum to zero),
     * {@code balancesMatchPostings} (each stored balance equals the sum of its postings) and
     * {@code walletsMatchLedger} (each wallet balance equals its org's BUYER_WALLET balance).
     */
    public record PlatformAccountsResponse(List<PlatformAccountResponse> accounts, boolean totalsCheck,
                                           boolean balancesMatchPostings, boolean walletsMatchLedger) {}

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final OrgRepository orgRepository;
    private final WalletRepository walletRepository;
    private final BillingModeService billingModeService;
    private final MoneyProperties props;
    private final LedgerService ledgerService;
    private final LedgerAccounts ledgerAccounts;
    private final LedgerAccountRepository accountRepository;
    private final LedgerAccountBalanceRepository balanceRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final WalletView walletView;
    private final TransactionTemplate tx;

    @PersistenceContext
    private EntityManager entityManager;

    public AdminMoneyController(OrgRepository orgRepository, WalletRepository walletRepository,
                                BillingModeService billingModeService, MoneyProperties props,
                                LedgerService ledgerService, LedgerAccounts ledgerAccounts,
                                LedgerAccountRepository accountRepository,
                                LedgerAccountBalanceRepository balanceRepository,
                                LedgerTransactionRepository transactionRepository,
                                WalletView walletView, PlatformTransactionManager transactionManager) {
        this.orgRepository = orgRepository;
        this.walletRepository = walletRepository;
        this.billingModeService = billingModeService;
        this.props = props;
        this.ledgerService = ledgerService;
        this.ledgerAccounts = ledgerAccounts;
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.walletView = walletView;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @GetMapping("/api/v1/admin/orgs/{orgId}/money")
    public OrgMoneySettingsResponse money(@PathVariable UUID orgId) {
        return settings(orgId);
    }

    @PatchMapping("/api/v1/admin/orgs/{orgId}/money")
    public OrgMoneySettingsResponse update(@PathVariable UUID orgId, @RequestBody OrgMoneySettingsRequest request) {
        BigDecimal fee = request.feePercent();
        if (fee != null && (fee.signum() < 0 || fee.compareTo(HUNDRED) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "feePercent must be between 0 and 100");
        }
        BigDecimal limit = request.creditLimit();
        if (limit != null && limit.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "creditLimit must not be negative");
        }
        OrgType type = orgType(orgId);
        if ((limit != null || request.billingMode() != null) && type != OrgType.BUYER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only buyer orgs have a billing mode or credit limit");
        }
        // One transaction under the org row lock: fee, limit and mode commit together, and the entity setMode
        // saves is the one carrying the new fee and limit.
        tx.executeWithoutResult(status -> {
            Org org = orgRepository.findByIdForUpdate(orgId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
            if (fee != null) org.setPlatformFeePercent(fee);
            if (limit != null) org.setCreditLimit(limit);
            if (request.billingMode() != null) {
                billingModeService.setMode(orgId, request.billingMode());
            }
        });
        return settings(orgId);
    }

    @GetMapping("/api/v1/admin/ledger/accounts")
    public PlatformAccountsResponse platformAccounts() {
        List<PlatformAccountResponse> accounts = balanceRepository.platformBalances().stream()
                .map(row -> new PlatformAccountResponse((LedgerAccountKind) row[0], (BigDecimal) row[1]))
                .toList();
        return new PlatformAccountsResponse(accounts, balanceRepository.totalOfAllBalances().signum() == 0,
                balanceRepository.accountsNotMatchingPostings().isEmpty(),
                balanceRepository.walletsNotMatchingLedger().isEmpty());
    }

    /**
     * Credits (or, if negative, debits) a prepaid wallet against EXTERNAL, once per {@code requestId}. A repeat of
     * the same request, sequential or concurrent, returns the current wallet without posting again.
     */
    @PostMapping("/api/v1/admin/orgs/{orgId}/wallet/adjustments")
    public WalletResponse adjust(@PathVariable UUID orgId, @RequestBody AdjustmentRequest request) {
        if (request.requestId() == null || request.requestId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestId is required");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() == 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amount must be non-zero with at most 2 decimal places");
        }
        if (orgType(orgId) != OrgType.BUYER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only buyer orgs have a wallet");
        }
        UUID adminUserId = Caller.current().userId();
        // Created outside the transaction so no second connection is needed while the wallet is locked.
        UUID external = ledgerAccounts.platformAccount(LedgerAccountKind.EXTERNAL);
        try {
            tx.executeWithoutResult(status -> applyAdjustment(orgId, external, request, adminUserId));
        } catch (DataIntegrityViolationException e) {
            // Lost a race on the idempotency key: the winner's adjustment stands. Read it back in a new transaction.
            if (!isReplay(orgId, request)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId was already used for another request");
            }
        }
        return walletView.of(orgId);
    }

    private void applyAdjustment(UUID orgId, UUID external, AdjustmentRequest request, UUID adminUserId) {
        String key = "adjustment:" + request.requestId();
        // A retry replays even if the org has since switched mode; checked again under the lock below.
        if (alreadyApplied(key, orgId, request.amount())) {
            return;
        }
        if (billingModeService.modeFor(orgId) == BillingMode.POSTPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Postpaid orgs have no wallet to adjust");
        }
        walletRepository.insertEmptyIfMissing(orgId);
        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        if (alreadyApplied(key, orgId, request.amount())) {
            return;
        }
        // setMode takes the wallet lock too, so a switch that committed after the check above shows up here.
        if (billingModeService.currentModeFor(orgId) == BillingMode.POSTPAID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Postpaid orgs have no wallet to adjust");
        }
        BigDecimal amount = request.amount();
        BigDecimal available = wallet.getBalance().subtract(wallet.getReserved());
        if (amount.signum() < 0 && amount.negate().compareTo(available) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Debit exceeds the available balance of " + available.setScale(2));
        }
        UUID walletAccount = ledgerAccounts.buyerWallet(wallet);   // before the balance changes: it seeds the opening
        Map<String, Object> reference = new java.util.HashMap<>();
        reference.put("note", request.note());
        reference.put("adminUserId", adminUserId.toString());
        reference.put("amount", amount.toPlainString());
        LedgerTransaction posted = ledgerService.post(LedgerTransactionType.ADJUSTMENT, key, null, orgId, reference,
                List.of(new Posting(walletAccount, amount), new Posting(external, amount.negate())));
        if (!orgId.equals(posted.getOrgId())) {
            // Another org's request with the same id committed between our check and the post.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId was already used for another org");
        }
        wallet.setBalance(wallet.getBalance().add(amount));
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
    }

    private OrgType orgType(UUID orgId) {
        return orgRepository.findById(orgId).map(Org::getType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    }

    /** True when this exact request already ran; 409 when the key was used for another org or amount. */
    private boolean alreadyApplied(String key, UUID orgId, BigDecimal amount) {
        var existing = transactionRepository.findByIdempotencyKey(key);
        if (existing.isEmpty()) {
            return false;
        }
        if (!matches(existing.get(), orgId, amount)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "requestId was already used for another request");
        }
        return true;
    }

    private boolean isReplay(UUID orgId, AdjustmentRequest request) {
        var existing = tx.execute(status ->
                transactionRepository.findByIdempotencyKey("adjustment:" + request.requestId()));
        return existing != null && existing.isPresent() && matches(existing.get(), orgId, request.amount());
    }

    private static boolean matches(LedgerTransaction transaction, UUID orgId, BigDecimal amount) {
        Object recorded = transaction.getReference() == null ? null : transaction.getReference().get("amount");
        return orgId.equals(transaction.getOrgId()) && recorded != null
                && new BigDecimal(String.valueOf(recorded)).compareTo(amount) == 0;
    }

    private OrgMoneySettingsResponse settings(UUID orgId) {
        Org org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        BigDecimal effectiveFee = org.getPlatformFeePercent() != null
                ? org.getPlatformFeePercent() : props.platformFeePercent();
        BigDecimal wallet = walletRepository.balanceOfOrg(orgId).orElse(BigDecimal.ZERO);
        BigDecimal payable = accountRepository.findByOrgIdAndKind(orgId, LedgerAccountKind.DEVELOPER_PAYABLE)
                .map(a -> ledgerService.balanceOf(a.getId())).orElse(BigDecimal.ZERO);
        return new OrgMoneySettingsResponse(orgId, org.getName(), org.getType(), org.getPlatformFeePercent(),
                effectiveFee, org.getBillingMode(), billingModeService.modeFor(orgId), org.getCreditLimit(),
                wallet, payable);
    }
}
