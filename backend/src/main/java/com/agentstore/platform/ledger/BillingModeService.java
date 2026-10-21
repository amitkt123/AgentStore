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

    @PersistenceContext
    private EntityManager entityManager;

    public BillingModeService(OrgRepository orgRepository, WalletRepository walletRepository, MoneyProperties props) {
        this.orgRepository = orgRepository;
        this.walletRepository = walletRepository;
        this.props = props;
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
     * Switches the org's billing mode. Switching to POSTPAID is refused while the wallet holds a balance or a
     * reservation, since postpaid charges never touch the wallet and that money would be stranded.
     */
    @Transactional
    public void setMode(UUID orgId, BillingMode mode) {
        Objects.requireNonNull(mode, "mode");
        Org org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        if (mode == BillingMode.POSTPAID) {
            // Under the wallet lock, so no reservation or charge can slip in between the check and the switch.
            walletRepository.findByOrgId(orgId).ifPresent(wallet -> {
                entityManager.refresh(wallet, LockModeType.PESSIMISTIC_WRITE);
                if (wallet.getBalance().signum() != 0 || wallet.getReserved().signum() != 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Wallet still holds money; refund or use it first");
                }
            });
        }
        org.setBillingMode(mode);
        orgRepository.save(org);
    }
}
