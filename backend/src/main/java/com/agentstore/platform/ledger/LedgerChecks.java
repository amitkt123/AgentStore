package com.agentstore.platform.ledger;

import org.springframework.stereotype.Component;

/** The three ledger integrity checks, shared by the admin accounts endpoint and nightly reconciliation. */
@Component
public class LedgerChecks {

    /**
     * {@code totalsZero}: all balances sum to zero. {@code balancesMatchPostings}: each stored balance equals the
     * sum of its postings. {@code walletsMatchLedger}: each wallet balance equals its org's BUYER_WALLET balance.
     */
    public record Result(boolean totalsZero, boolean balancesMatchPostings, boolean walletsMatchLedger) {}

    private final LedgerAccountBalanceRepository balanceRepository;

    public LedgerChecks(LedgerAccountBalanceRepository balanceRepository) {
        this.balanceRepository = balanceRepository;
    }

    public Result run() {
        return new Result(balanceRepository.totalOfAllBalances().signum() == 0,
                balanceRepository.accountsNotMatchingPostings().isEmpty(),
                balanceRepository.walletsNotMatchingLedger().isEmpty());
    }
}
