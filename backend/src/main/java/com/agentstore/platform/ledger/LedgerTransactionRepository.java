package com.agentstore.platform.ledger;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {
    Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey);

    /** One row per run the org was charged for in [from, to): its charged total, units, and who last decided a gate. */
    interface RunChargeRow {
        String getRunDisplayId();

        UUID getAgentId();

        String getAgentName();

        String getManifestJson();

        String getTerminalReason();

        BigDecimal getAmount();

        BigDecimal getUnits();

        Instant getAt();

        UUID getDecidedBy();
    }

    @Query(nativeQuery = true, value = """
            select r.display_id as runDisplayId, a.id as agentId, a.name as agentName,
                   cast(av.manifest_json as text) as manifestJson, r.terminal_reason as terminalReason,
                   c.amount as amount, c.at as at,
                   (select coalesce(sum(u.quantity), 0) from usage_event u where u.run_id = r.id) as units,
                   (select g.decided_by_user_id from gate_request g
                     where g.run_id = r.id and g.decided_at is not null
                     order by g.decided_at desc limit 1) as decidedBy
            from (select t.run_id, -sum(p.amount) as amount, max(t.created_at) as at
                  from ledger_transaction t
                  join ledger_posting p on p.transaction_id = t.id
                  join ledger_account la on la.id = p.account_id
                  where t.type = 'RUN_CHARGE' and t.org_id = :orgId and la.org_id = :orgId
                    and la.kind in ('BUYER_WALLET', 'BUYER_RECEIVABLE')
                    and t.created_at >= :from and t.created_at < :to
                  group by t.run_id) c
            join run r on r.id = c.run_id
            join agent_version av on av.id = r.agent_version_id
            join agent a on a.id = av.agent_id
            order by c.at desc""")
    List<RunChargeRow> runChargesBetween(@Param("orgId") UUID orgId, @Param("from") Instant from,
                                         @Param("to") Instant to);
}
