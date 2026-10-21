package com.agentstore.platform.ledger;

import com.agentstore.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerAccountsTest extends AbstractIntegrationTest {

    @Autowired
    LedgerAccounts ledgerAccounts;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    LedgerTransactionRepository transactions;
    @Autowired
    LedgerPostingRepository postings;
    @Autowired
    WalletRepository walletRepository;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    UUID walletWithBalance(String balance) {
        UUID orgId = UUID.randomUUID();
        Wallet wallet = new Wallet(null, orgId, new BigDecimal(balance), null);
        wallet.setUpdatedAt(Instant.now());
        walletRepository.save(wallet);
        return orgId;
    }

    UUID buyerWalletInTx(UUID orgId) {
        return tx.execute(s -> ledgerAccounts.buyerWallet(walletRepository.findByOrgId(orgId).orElseThrow()));
    }

    BigDecimal externalBalance() {
        return ledgerService.balanceOf(ledgerAccounts.platformAccount(LedgerAccountKind.EXTERNAL));
    }

    @Test
    void orgAccountIsCreatedOnceWithZeroBalance() {
        UUID orgId = UUID.randomUUID();
        UUID first = ledgerAccounts.orgAccount(orgId, LedgerAccountKind.DEVELOPER_PAYABLE);
        UUID second = ledgerAccounts.orgAccount(orgId, LedgerAccountKind.DEVELOPER_PAYABLE);

        assertThat(second).isEqualTo(first);
        assertThat(ledgerService.balanceOf(first)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from ledger_account where org_id = ?", Integer.class, orgId))
                .isEqualTo(1);
    }

    @Test
    void platformAccountIsSharedAndHasNoOrg() {
        UUID first = ledgerAccounts.platformAccount(LedgerAccountKind.PLATFORM_LLM_COST);
        UUID second = ledgerAccounts.platformAccount(LedgerAccountKind.PLATFORM_LLM_COST);

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select org_id from ledger_account where id = ?", UUID.class, first)).isNull();
        assertThat(ledgerService.balanceOf(first)).isNotNull();
    }

    @Test
    void accountKindMustMatchOwner() {
        UUID orgId = UUID.randomUUID();
        assertThatThrownBy(() -> ledgerAccounts.orgAccount(orgId, LedgerAccountKind.PLATFORM_REVENUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledgerAccounts.platformAccount(LedgerAccountKind.DEVELOPER_PAYABLE))
                .isInstanceOf(IllegalArgumentException.class);
        // The buyer wallet must go through buyerWallet so its opening balance is never skipped.
        assertThatThrownBy(() -> ledgerAccounts.orgAccount(orgId, LedgerAccountKind.BUYER_WALLET))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentCreationYieldsOneAccount() throws Exception {
        UUID orgId = UUID.randomUUID();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<UUID>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return ledgerAccounts.orgAccount(orgId, LedgerAccountKind.DEVELOPER_PAYABLE);
                }));
            }
            start.countDown();
            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> f : results) ids.add(f.get());
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from ledger_account where org_id = ?", Integer.class, orgId))
                .isEqualTo(1);
    }

    @Test
    void existingWalletGetsOpeningBalanceOnce() {
        UUID orgId = walletWithBalance("10000.00");
        BigDecimal externalBefore = externalBalance();

        UUID first = buyerWalletInTx(orgId);
        UUID second = buyerWalletInTx(orgId);

        assertThat(second).isEqualTo(first);
        assertThat(ledgerService.balanceOf(first)).isEqualByComparingTo("10000.00");
        LedgerTransaction opening = transactions.findByIdempotencyKey("opening:" + orgId).orElseThrow();
        assertThat(opening.getType()).isEqualTo(LedgerTransactionType.ADJUSTMENT);
        assertThat(opening.getOrgId()).isEqualTo(orgId);
        assertThat(jdbc.queryForObject("select count(*) from ledger_transaction where org_id = ? and type = 'ADJUSTMENT'",
                Integer.class, orgId)).isEqualTo(1);
        assertThat(postings.findByTransactionId(opening.getId())).hasSize(2);
        assertThat(externalBalance().subtract(externalBefore)).isEqualByComparingTo("-10000.00");
    }

    @Test
    void zeroWalletGetsNoOpeningPosting() {
        UUID orgId = walletWithBalance("0.00");

        UUID account = buyerWalletInTx(orgId);

        assertThat(ledgerService.balanceOf(account)).isEqualByComparingTo("0");
        assertThat(transactions.findByIdempotencyKey("opening:" + orgId)).isEmpty();
        assertThat(postings.sumByAccountId(account)).isEqualByComparingTo("0");
    }

    @Test
    void buyerWalletRequiresTheWalletLockedInTheCurrentTransaction() {
        Wallet unlocked = new Wallet(null, UUID.randomUUID(), new BigDecimal("10.00"), null);
        unlocked.setUpdatedAt(Instant.now());
        Wallet detached = walletRepository.save(unlocked);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledgerAccounts.buyerWallet(detached)))
                .isInstanceOf(IllegalStateException.class);
        // Managed but read without a lock is not enough either.
        UUID orgId = detached.getOrgId();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> ledgerAccounts.buyerWallet(
                walletRepository.findById(detached.getId()).orElseThrow())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(transactions.findByIdempotencyKey("opening:" + orgId)).isEmpty();
    }

    @Test
    void openingBalanceRollsBackWithTheCaller() {
        UUID orgId = walletWithBalance("250.00");

        tx.executeWithoutResult(s -> {
            ledgerAccounts.buyerWallet(walletRepository.findByOrgId(orgId).orElseThrow());
            s.setRollbackOnly();
        });
        UUID account = buyerWalletInTx(orgId);

        assertThat(ledgerService.balanceOf(account)).isEqualByComparingTo("250.00");
        assertThat(transactions.findByIdempotencyKey("opening:" + orgId)).isPresent();
    }
}
