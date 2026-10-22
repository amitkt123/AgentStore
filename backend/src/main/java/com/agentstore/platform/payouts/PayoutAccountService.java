package com.agentstore.platform.payouts;

import com.agentstore.platform.common.AesGcmCipher;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.OrgType;
import com.agentstore.platform.ledger.LedgerAccount;
import com.agentstore.platform.ledger.LedgerAccountKind;
import com.agentstore.platform.ledger.LedgerAccountRepository;
import com.agentstore.platform.ledger.LedgerService;
import com.agentstore.platform.verification.DeveloperVerificationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PayoutAccountService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PayoutAccountService.class);

    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^\\d{9,18}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private static final Pattern VPA = Pattern.compile("^[a-zA-Z0-9.\\-_]{2,256}@[a-zA-Z]{2,64}$");

    private final PayoutAccountRepository accounts;
    private final WithdrawalRepository withdrawals;
    private final OrgRepository orgs;
    private final DeveloperVerificationRepository verifications;
    private final LedgerAccountRepository ledgerAccounts;
    private final LedgerService ledger;
    private final PayoutProvider provider;
    private final PayoutProperties props;
    private final AesGcmCipher cipher;
    private final TransactionTemplate tx;

    public PayoutAccountService(PayoutAccountRepository accounts, WithdrawalRepository withdrawals, OrgRepository orgs,
                                DeveloperVerificationRepository verifications, LedgerAccountRepository ledgerAccounts,
                                LedgerService ledger, PayoutProvider provider, PayoutProperties props,
                                AesGcmCipher cipher, TransactionTemplate tx) {
        this.accounts = accounts;
        this.withdrawals = withdrawals;
        this.orgs = orgs;
        this.verifications = verifications;
        this.ledgerAccounts = ledgerAccounts;
        this.ledger = ledger;
        this.provider = provider;
        this.props = props;
        this.cipher = cipher;
        this.tx = tx;
    }

    /** Not transactional: the provider is called outside any transaction, then one transaction saves the rows. */
    public PayoutAccountView add(UUID orgId, UUID userId, PayoutAccountRequest req) {
        Org org = orgs.findById(orgId).orElseThrow(notVerified());
        String blocked = orgBlock(org);
        if (blocked != null) throw blockedException(blocked);

        Fields f = validate(req);
        long today = accounts.countByOrgIdAndCreatedAtGreaterThanEqual(orgId, startOfTodayUtc());
        if (today >= props.validationAttemptsPerDay()) {
            throw new PayoutApiException(HttpStatus.TOO_MANY_REQUESTS, "VALIDATION_LIMIT_REACHED",
                    "Too many payout account attempts today; try again tomorrow");
        }

        PayoutProvider.ProviderValidation validation;
        String contactId, fundAccountId;
        try {
            contactId = provider.createContact(orgId, f.holderName());
            fundAccountId = provider.createFundAccount(contactId, new PayoutProvider.FundAccountDetails(
                    req.type(), f.accountNumber(), f.ifsc(), f.vpa(), f.holderName()));
            validation = provider.validateFundAccount(fundAccountId, req.type());
        } catch (PayoutProviderUnavailableException e) {
            throw new PayoutApiException(HttpStatus.BAD_GATEWAY, "PROVIDER_UNAVAILABLE",
                    "The payout provider is unavailable; try again shortly", e);
        } catch (PayoutProviderRejectedException e) {
            throw new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD",
                    "The payout provider rejected these account details");
        }

        UUID id = tx.execute(status -> {
            // Serialise concurrent adds for the org, then recheck the cap under the lock.
            Org locked = orgs.findByIdForUpdate(orgId).orElseThrow(notVerified());
            String nowBlocked = orgBlock(locked);
            if (nowBlocked != null) throw blockedException(nowBlocked);
            if (accounts.countByOrgIdAndCreatedAtGreaterThanEqual(orgId, startOfTodayUtc())
                    >= props.validationAttemptsPerDay()) {
                log.info("Payout account attempt for org {} refused after provider calls; orphaned provider contact {} "
                        + "fund account {}", orgId, contactId, fundAccountId);
                throw new PayoutApiException(HttpStatus.TOO_MANY_REQUESTS, "VALIDATION_LIMIT_REACHED",
                        "Too many payout account attempts today; try again tomorrow");
            }
            accounts.findByOrgIdAndReplacedAtIsNull(orgId).ifPresent(old -> {
                old.setReplacedAt(Instant.now());
                accounts.saveAndFlush(old);
            });
            PayoutAccount a = new PayoutAccount();
            a.setOrgId(orgId);
            a.setType(req.type());
            if (req.type() == PayoutAccountType.BANK) {
                AesGcmCipher.Encrypted enc = cipher.encrypt(f.accountNumber().getBytes(StandardCharsets.UTF_8));
                a.setAccountNumberEncrypted(enc.ciphertext());
                a.setAccountNonce(enc.nonce());
                a.setAccountLast4(f.accountNumber().substring(f.accountNumber().length() - 4));
                a.setIfsc(f.ifsc());
            } else {
                a.setVpa(f.vpa());
            }
            a.setHolderName(f.holderName());
            a.setStatus(PayoutAccountStatus.PENDING);
            a.setProviderContactId(contactId);
            a.setProviderFundAccountId(fundAccountId);
            a.setProviderValidationId(validation.validationId());
            a = accounts.saveAndFlush(a);
            applyValidation(a.getId(), validation);
            return a.getId();
        });
        return PayoutAccountView.of(accounts.findById(id).orElseThrow());
    }

    /**
     * Idempotent: only a PENDING account is changed, and a PENDING validation changes nothing. A replaced (no longer
     * active) PENDING row is still resolved, since it is the row the provider validated, but it never becomes active.
     */
    @Transactional
    public void applyValidation(UUID accountId, PayoutProvider.ProviderValidation v) {
        PayoutAccount a = accounts.findByIdForUpdate(accountId).orElse(null);
        if (a == null || a.getStatus() != PayoutAccountStatus.PENDING) return;
        switch (v.status()) {
            case "COMPLETED" -> {
                String legal = verifications.findByOrgId(a.getOrgId()).map(d -> d.getLegalName()).orElse(null);
                a.setValidatedName(v.registeredName());
                boolean match = NameMatcher.matches(legal, v.registeredName());
                a.setNameMatch(match);
                if (match) {
                    a.setStatus(PayoutAccountStatus.VERIFIED);
                    a.setVerifiedAt(Instant.now());
                } else {
                    a.setStatus(PayoutAccountStatus.FAILED);
                    a.setFailureReason("NAME_MISMATCH");
                }
            }
            case "FAILED" -> {
                a.setStatus(PayoutAccountStatus.FAILED);
                a.setFailureReason(v.failureReason() == null ? "VALIDATION_FAILED" : v.failureReason());
            }
            default -> {
                if (!"PENDING".equals(v.status())) log.warn("Ignoring unknown payout validation status {}", v.status());
                return;
            }
        }
        accounts.save(a);
    }

    @Transactional(readOnly = true)
    public Optional<PayoutAccount> activeAccount(UUID orgId) {
        return accounts.findByOrgIdAndReplacedAtIsNull(orgId);
    }

    /**
     * The guard every money-out path shares: the org is a verified developer, its money is not frozen, and its
     * active payout account is VERIFIED. Returns that account. Joins the caller's transaction when there is one.
     *
     * @throws PayoutApiException DEVELOPER_NOT_VERIFIED (403), MONEY_FROZEN (409) or NO_VERIFIED_PAYOUT_ACCOUNT (409)
     */
    @Transactional
    public PayoutAccount requireWithdrawable(UUID orgId) {
        Org org = orgs.findById(orgId).orElseThrow(notVerified());
        String blocked = orgBlock(org);
        if (blocked != null) throw blockedException(blocked);
        return accounts.findByOrgIdAndReplacedAtIsNull(orgId)
                .filter(a -> a.getStatus() == PayoutAccountStatus.VERIFIED)
                .orElseThrow(() -> new PayoutApiException(HttpStatus.CONFLICT, "NO_VERIFIED_PAYOUT_ACCOUNT",
                        "Add a payout account and wait for it to be verified before withdrawing"));
    }

    @Transactional(readOnly = true)
    public PayoutsSummaryResponse summary(UUID orgId) {
        Org org = orgs.findById(orgId).orElseThrow(notVerified());
        Optional<PayoutAccount> account = accounts.findByOrgIdAndReplacedAtIsNull(orgId);
        BigDecimal available = ledgerAccounts.findByOrgIdAndKind(orgId, LedgerAccountKind.DEVELOPER_PAYABLE)
                .map(LedgerAccount::getId).map(ledger::balanceOf).orElse(BigDecimal.ZERO);
        Instant start = startOfTodayUtc();
        BigDecimal withdrawn = withdrawals.sumCountedSince(orgId, start);
        BigDecimal limit = props.withdrawalDailyLimit();

        String blocked = orgBlock(org);
        if (blocked == null && account.filter(a -> a.getStatus() == PayoutAccountStatus.VERIFIED).isEmpty()) {
            blocked = "NO_VERIFIED_PAYOUT_ACCOUNT";
        }
        if (blocked == null && available.compareTo(props.withdrawalMin()) < 0) blocked = "INSUFFICIENT_EARNINGS";
        if (blocked == null && withdrawn.compareTo(limit) >= 0) blocked = "DAILY_LIMIT_REACHED";

        long used = accounts.countByOrgIdAndCreatedAtGreaterThanEqual(orgId, start);
        int left = (int) Math.max(0, props.validationAttemptsPerDay() - used);
        return new PayoutsSummaryResponse(account.map(PayoutAccountView::of).orElse(null), available,
                props.withdrawalMin(), limit, withdrawn, blocked == null, blocked, left, provider.name());
    }

    private static String orgBlock(Org org) {
        if (org.getType() != OrgType.DEVELOPER || org.getDeveloperVerifiedAt() == null) return "DEVELOPER_NOT_VERIFIED";
        if (org.getMoneyFrozenAt() != null) return "MONEY_FROZEN";
        return null;
    }

    private static PayoutApiException blockedException(String code) {
        return code.equals("MONEY_FROZEN")
                ? new PayoutApiException(HttpStatus.CONFLICT, code, "Money movement is frozen for this organisation")
                : new PayoutApiException(HttpStatus.FORBIDDEN, code, "Only verified developers can add payout accounts or withdraw");
    }

    private static java.util.function.Supplier<PayoutApiException> notVerified() {
        return () -> blockedException("DEVELOPER_NOT_VERIFIED");
    }

    static Instant startOfTodayUtc() {
        return LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private record Fields(String accountNumber, String ifsc, String vpa, String holderName) {}

    private static Fields validate(PayoutAccountRequest req) {
        if (req == null || req.type() == null) throw invalid("type must be BANK or VPA");
        String holder = req.holderName() == null ? "" : req.holderName().trim();
        if (holder.isEmpty() || holder.length() > 120) throw invalid("holderName must be 1 to 120 characters");
        if (req.type() == PayoutAccountType.BANK) {
            String number = req.accountNumber() == null ? "" : req.accountNumber().trim();
            if (!ACCOUNT_NUMBER.matcher(number).matches()) throw invalid("accountNumber must be 9 to 18 digits");
            String ifsc = req.ifsc() == null ? "" : req.ifsc().trim().toUpperCase();
            if (!IFSC.matcher(ifsc).matches()) throw invalid("ifsc is not a valid IFSC code");
            return new Fields(number, ifsc, null, holder);
        }
        String vpa = req.vpa() == null ? "" : req.vpa().trim();
        if (!VPA.matcher(vpa).matches()) throw invalid("vpa is not a valid UPI address");
        return new Fields(null, null, vpa, holder);
    }

    private static PayoutApiException invalid(String message) {
        return new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", message);
    }
}
