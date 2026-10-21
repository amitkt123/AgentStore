package com.agentstore.platform.ledger;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Whether an org pays up front from its wallet (PREPAID) or runs on credit and is invoiced (POSTPAID). */
@Service
public class BillingModeService {

    private final OrgRepository orgRepository;
    private final WalletRepository walletRepository;
    private final MoneyProperties props;
    private final LedgerAccountRepository accountRepository;
    private final LedgerAccountBalanceRepository balanceRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public BillingModeService(OrgRepository orgRepository, WalletRepository walletRepository, MoneyProperties props,
                              LedgerAccountRepository accountRepository,
                              LedgerAccountBalanceRepository balanceRepository) {
        this.orgRepository = orgRepository;
        this.walletRepository = walletRepository;
        this.props = props;
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
    }

    /** The org's own mode, else the configured default (also for an unknown org). */
    @Transactional(readOnly = true)
    public BillingMode modeFor(UUID orgId) {
        return orgRepository.findById(orgId).map(Org::getBillingMode).orElse(props.billingMode());
    }

    /** The org's postpaid credit limit, else the configured default. */
    @Transactional(readOnly = true)
    public BigDecimal creditLimit(UUID orgId) {
        return orgRepository.findById(orgId).map(Org::getCreditLimit).orElse(props.postpaidDefaultCreditLimit());
    }

    /**
     * Switches the org's billing mode, checked under the wallet lock (the row is created first if missing, so a
     * concurrent first reservation or charge cannot slip past the check):
     * <ul>
     *   <li>to POSTPAID is refused while the wallet holds a balance or a reservation, since postpaid charges never
     *       touch the wallet and that money would be stranded;</li>
     *   <li>from POSTPAID to PREPAID is refused while runs hold reservations or money is owed, since those would
     *       then be charged against an empty wallet.</li>
     * </ul>
     */
    @Transactional
    public void setMode(UUID orgId, BillingMode mode) {
        Objects.requireNonNull(mode, "mode");
        Org org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        walletRepository.insertEmptyIfMissing(orgId);
        Wallet wallet = walletRepository.findByOrgId(orgId).orElseThrow();
        entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
        BillingMode current = org.getBillingMode() != null ? org.getBillingMode() : props.billingMode();
        if (mode == BillingMode.POSTPAID
                && (wallet.getBalance().signum() != 0 || wallet.getReserved().signum() != 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Wallet still holds money; refund or use it first");
        }
        if (mode == BillingMode.PREPAID && current == BillingMode.POSTPAID
                && (wallet.getReserved().signum() != 0 || owed(orgId).signum() != 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Runs are in progress or money is owed; wait for them to finish and the bill to be paid");
        }
        org.setBillingMode(mode);
        orgRepository.save(org);
    }

    /**
     * What a postpaid org owes: the negated BUYER_RECEIVABLE balance, or 0 before its first charge (the account is
     * not created to read it). Stable while the caller holds the org's wallet lock, as charges take it first.
     */
    @Transactional(readOnly = true)
    public BigDecimal owed(UUID orgId) {
        return accountRepository.findByOrgIdAndKind(orgId, LedgerAccountKind.BUYER_RECEIVABLE)
                .flatMap(account -> balanceRepository.findById(account.getId()))
                .map(balance -> {
                    entityManager.flush();     // refresh would otherwise discard pending changes
                    entityManager.refresh(balance);
                    return balance.getBalance().negate();
                })
                .orElse(BigDecimal.ZERO);
    }
}
