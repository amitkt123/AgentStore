package com.agentstore.platform.ledger;

import com.agentstore.platform.execution.Run;
import com.agentstore.platform.llm.LlmCallRepository;
import com.agentstore.platform.llm.LlmSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deducts the platform-paid LLM cost of a finished buyer run from the developer's earnings. */
@Service
public class RunLlmCostService {

    private final LlmCallRepository llmCalls;
    private final LedgerService ledgerService;
    private final LedgerAccounts ledgerAccounts;

    public RunLlmCostService(LlmCallRepository llmCalls, LedgerService ledgerService, LedgerAccounts ledgerAccounts) {
        this.llmCalls = llmCalls;
        this.ledgerService = ledgerService;
        this.ledgerAccounts = ledgerAccounts;
    }

    /**
     * Posts {@code llm:{runId}}: DEVELOPER_PAYABLE -L, PLATFORM_LLM_COST +L, where L is the run's PLATFORM-sourced
     * LLM cost (BYOK, mock and replay calls cost the platform nothing). May leave the payable negative. Idempotent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void post(Run run) {
        BigDecimal cost = llmCalls.sumCostByRunIdAndSource(run.getId(), LlmSource.PLATFORM).setScale(2, RoundingMode.HALF_UP);
        if (cost.signum() <= 0) {
            return;
        }
        var agent = run.getAgentVersion().getAgent();
        UUID developerOrgId = agent.getDeveloperOrg().getId();
        ledgerService.post(LedgerTransactionType.LLM_COST, "llm:" + run.getId(), run.getId(), developerOrgId,
                Map.of("agentId", agent.getId().toString()),
                List.of(new Posting(ledgerAccounts.orgAccount(developerOrgId, LedgerAccountKind.DEVELOPER_PAYABLE), cost.negate()),
                        new Posting(ledgerAccounts.platformAccount(LedgerAccountKind.PLATFORM_LLM_COST), cost)));
    }
}
