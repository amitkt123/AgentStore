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

    /** One developer-payable transaction with its postings broken out by what they are for. */
    interface DeveloperTransactionRow {
        UUID getId();

        String getType();

        Instant getAt();

        String getRunDisplayId();

        UUID getAgentId();

        String getAgentName();

        BigDecimal getBuyerPaid();

        BigDecimal getPlatformFee();

        BigDecimal getAiUsage();

        BigDecimal getTax();

        BigDecimal getEarned();
    }

    /**
     * Every transaction in [from, to) with a posting on the developer's payable account, oldest first. Buyer paid is
     * the negated sum of a charge's negative buyer-side postings; the agent comes from the transaction's run.
     */
    @Query(nativeQuery = true, value = """
            select t.id as id, t.type as type, t.created_at as at, r.display_id as runDisplayId,
                   a.id as agentId, a.name as agentName,
                   coalesce(sum(case when t.type = 'RUN_CHARGE' and la.kind in ('BUYER_WALLET', 'BUYER_RECEIVABLE')
                                      and p.amount < 0 then -p.amount end), 0) as buyerPaid,
                   coalesce(sum(case when la.kind = 'PLATFORM_REVENUE' then p.amount end), 0) as platformFee,
                   coalesce(sum(case when la.kind = 'PLATFORM_LLM_COST' then p.amount end), 0) as aiUsage,
                   coalesce(sum(case when la.kind in ('TAX_TDS_PAYABLE', 'TAX_TCS_PAYABLE') then p.amount end), 0) as tax,
                   coalesce(sum(case when p.account_id = :accountId then p.amount end), 0) as earned
            from ledger_transaction t
            join ledger_posting p on p.transaction_id = t.id
            join ledger_account la on la.id = p.account_id
            left join run r on r.id = t.run_id
            left join agent_version av on av.id = r.agent_version_id
            left join agent a on a.id = av.agent_id
            where t.created_at >= :from and t.created_at < :to
              and exists (select 1 from ledger_posting dp
                          where dp.transaction_id = t.id and dp.account_id = :accountId)
            group by t.id, t.type, t.created_at, r.display_id, a.id, a.name
            order by t.created_at, t.id""")
    List<DeveloperTransactionRow> developerTransactionsBetween(@Param("accountId") UUID accountId,
                                                               @Param("from") Instant from,
                                                               @Param("to") Instant to);
}
