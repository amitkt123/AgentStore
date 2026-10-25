package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.ledger.AdminMoneyController;
import com.agentstore.platform.ledger.OrgMoneySettingsResponse;
import com.agentstore.platform.verification.DeveloperVerification;
import com.agentstore.platform.verification.DeveloperVerificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Admin money controls: freeze an org's money, review withdrawals, approve name-mismatched payout accounts. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AdminPayoutsController implements PayoutApiController {

    private static final int MAX_LIMIT = 500;

    private final OrgRepository orgs;
    private final WithdrawalRepository withdrawals;
    private final PayoutAccountRepository accounts;
    private final DeveloperVerificationRepository verifications;
    private final AdminMoneyController money;
    private final TransactionTemplate tx;

    public AdminPayoutsController(OrgRepository orgs, WithdrawalRepository withdrawals, PayoutAccountRepository accounts,
                                  DeveloperVerificationRepository verifications, AdminMoneyController money,
                                  TransactionTemplate tx) {
        this.orgs = orgs;
        this.withdrawals = withdrawals;
        this.accounts = accounts;
        this.verifications = verifications;
        this.money = money;
        this.tx = tx;
    }

    /** Freezing an already frozen org changes nothing: the first time and reason stay. */
    @PostMapping("/api/v1/admin/orgs/{id}/money/freeze")
    public OrgMoneySettingsResponse freeze(@PathVariable UUID id, @RequestBody(required = false) FreezeRequest request) {
        String reason = request == null || request.reason() == null ? "" : request.reason().trim();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "reason must be 1 to 500 characters");
        }
        tx.executeWithoutResult(s -> {
            Org org = lockOrg(id);
            if (org.getMoneyFrozenAt() == null) {
                org.setMoneyFrozenAt(Instant.now());
                org.setMoneyFrozenReason(reason);
                orgs.save(org);
            }
        });
        return money.money(id);
    }

    @PostMapping("/api/v1/admin/orgs/{id}/money/unfreeze")
    public OrgMoneySettingsResponse unfreeze(@PathVariable UUID id) {
        tx.executeWithoutResult(s -> {
            Org org = lockOrg(id);
            org.setMoneyFrozenAt(null);
            org.setMoneyFrozenReason(null);
            orgs.save(org);
        });
        return money.money(id);
    }

    @GetMapping("/api/v1/admin/withdrawals")
    public List<AdminWithdrawalView> withdrawals(@RequestParam(required = false) WithdrawalStatus status,
                                                 @RequestParam(required = false) UUID orgId,
                                                 @RequestParam(defaultValue = "100") int limit) {
        return tx.execute(s -> {
            List<Withdrawal> found = withdrawals.findForAdmin(status, orgId, PageRequest.of(0, clamp(limit)));
            Map<UUID, Org> orgById = orgsById(found.stream().map(Withdrawal::getOrgId).toList());
            Map<UUID, PayoutAccount> accountById = accounts
                    .findAllById(found.stream().map(Withdrawal::getPayoutAccountId).distinct().toList()).stream()
                    .collect(Collectors.toMap(PayoutAccount::getId, Function.identity()));
            return found.stream().map(w -> AdminWithdrawalView.of(
                    WithdrawalView.of(w, accountById.get(w.getPayoutAccountId())), w.getOrgId(),
                    name(orgById.get(w.getOrgId())))).toList();
        });
    }

    @GetMapping("/api/v1/admin/payout-accounts")
    public List<AdminPayoutAccountView> payoutAccounts(
            @RequestParam(defaultValue = "FAILED") PayoutAccountStatus status,
            @RequestParam(defaultValue = "100") int limit) {
        return tx.execute(s -> {
            List<PayoutAccount> found = accounts.findActiveByStatus(status, PageRequest.of(0, clamp(limit)));
            Map<UUID, Org> orgById = orgsById(found.stream().map(PayoutAccount::getOrgId).toList());
            return found.stream().map(a -> AdminPayoutAccountView.of(PayoutAccountView.of(a), a.getOrgId(),
                    name(orgById.get(a.getOrgId())),
                    verifications.findByOrgId(a.getOrgId()).map(DeveloperVerification::getLegalName).orElse(null)))
                    .toList();
        });
    }

    /** Only an active FAILED account whose failure is NAME_MISMATCH can be approved. */
    @PostMapping("/api/v1/admin/payout-accounts/{id}/approve")
    public AdminPayoutAccountView approve(@PathVariable UUID id, @RequestBody(required = false) ApproveAccountRequest request) {
        String note = request == null || request.note() == null ? "" : request.note().trim();
        if (note.isEmpty() || note.length() > 500) {
            throw new PayoutApiException(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "note must be 1 to 500 characters");
        }
        UUID adminUserId = ((JwtService.Claims) SecurityContextHolder.getContext().getAuthentication()
                .getPrincipal()).userId();
        return tx.execute(s -> {
            PayoutAccount a = accounts.findByIdForUpdate(id).orElseThrow(() ->
                    new PayoutApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Payout account not found"));
            if (a.getReplacedAt() != null || a.getStatus() != PayoutAccountStatus.FAILED
                    || !"NAME_MISMATCH".equals(a.getFailureReason())) {
                throw new PayoutApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_APPROVABLE",
                        "Only an active payout account that failed on a name mismatch can be approved");
            }
            a.setStatus(PayoutAccountStatus.VERIFIED);
            a.setApprovedByUserId(adminUserId);
            a.setApprovalNote(note);
            a.setVerifiedAt(Instant.now());
            accounts.save(a);
            Org org = orgs.findById(a.getOrgId()).orElse(null);
            return AdminPayoutAccountView.of(PayoutAccountView.of(a), a.getOrgId(), name(org),
                    verifications.findByOrgId(a.getOrgId()).map(DeveloperVerification::getLegalName).orElse(null));
        });
    }

    private Org lockOrg(UUID id) {
        return orgs.findByIdForUpdate(id).orElseThrow(() ->
                new PayoutApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Organisation not found"));
    }

    private Map<UUID, Org> orgsById(List<UUID> ids) {
        return orgs.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(Org::getId, Function.identity()));
    }

    private static String name(Org org) {
        return org == null ? null : org.getName();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
