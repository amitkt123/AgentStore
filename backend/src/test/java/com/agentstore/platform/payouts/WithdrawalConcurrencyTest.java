package com.agentstore.platform.payouts;

import com.agentstore.platform.identity.Org;
import com.agentstore.platform.ledger.LedgerChecks;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class WithdrawalConcurrencyTest extends WithdrawalTestSupport {

    @Autowired
    LedgerChecks ledgerChecks;

    private List<ResponseEntity<Map>> concurrently(int n, java.util.function.IntFunction<ResponseEntity<Map>> call)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int k = i;
            futures.add(pool.submit(() -> {
                start.await();
                return call.apply(k);
            }));
        }
        start.countDown();
        List<ResponseEntity<Map>> out = new ArrayList<>();
        for (var f : futures) out.add(f.get());
        pool.shutdown();
        return out;
    }

    @Test
    void concurrentWithdrawalsCannotExceedAvailable() throws Exception {
        Org org = readyDeveloper("1000.00");

        var results = concurrently(8, i -> withdraw(org, UUID.randomUUID(), "300.00"));

        int created = 0;
        for (var r : results) {
            assertThat(r.getStatusCode().value()).as("body %s", r.getBody()).isIn(201, 422);
            if (r.getStatusCode().value() == 201) created++;
            else assertThat(r.getBody().get("code")).isEqualTo("INSUFFICIENT_EARNINGS");
        }
        assertThat(created).isEqualTo(3);
        assertThat(payableBalance(org)).isEqualByComparingTo("100.00");
        assertThat(ledgerChecks.run()).isEqualTo(new LedgerChecks.Result(true, true, true));
    }

    @Test
    void concurrentSameRequestIdMovesMoneyOnce() throws Exception {
        Org org = readyDeveloper("1000.00");
        UUID requestId = UUID.randomUUID();

        var results = concurrently(6, i -> withdraw(org, requestId, "300.00"));

        int created = 0;
        Set<Object> ids = new HashSet<>();
        for (var r : results) {
            assertThat(r.getStatusCode().value()).as("body %s", r.getBody()).isIn(200, 201);
            if (r.getStatusCode().value() == 201) created++;
            ids.add(r.getBody().get("id"));
        }
        assertThat(created).isEqualTo(1);
        assertThat(ids).hasSize(1);
        assertThat(payableBalance(org)).isEqualByComparingTo("700.00");
        assertThat(ledgerChecks.run()).isEqualTo(new LedgerChecks.Result(true, true, true));
    }
}
