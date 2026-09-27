package com.agentstore.platform.llm;

import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunLlmMode;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.identity.Org;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shared setup for LLM gateway tests: test models, RUNNING runs of the LLM test manifests, counter access. */
@Component
public class LlmFixtures {

    public static final String CLAUDE = "test-claude";
    public static final String GPT = "test-gpt";
    public static final String DISABLED = "test-disabled";

    public record LlmAgent(ExecutionFixtures.Scenario scenario, Run run, String token) {
        public UUID runId() {
            return run.getId();
        }

        public Org developerOrg() {
            return scenario.version().getAgent().getDeveloperOrg();
        }
    }

    private final ExecutionFixtures executionFixtures;
    private final RunRepository runs;
    private final JdbcTemplate jdbc;

    public LlmFixtures(ExecutionFixtures executionFixtures, RunRepository runs, JdbcTemplate jdbc) {
        this.executionFixtures = executionFixtures;
        this.runs = runs;
        this.jdbc = jdbc;
    }

    /**
     * Upserts three test models: two enabled with prices, one disabled. The rows the migration seeds are never
     * touched, so a test that asserts the seed stays valid in a shared database.
     */
    public void ensureModels() {
        upsertModel(CLAUDE, "ANTHROPIC", "3.0000", "15.0000", true);
        upsertModel(GPT, "OPENAI", "2.5000", "10.0000", true);
        upsertModel(DISABLED, "ANTHROPIC", "1.0000", "5.0000", false);
    }

    private void upsertModel(String id, String provider, String inputPrice, String outputPrice, boolean enabled) {
        jdbc.update("""
                INSERT INTO llm_model (id, provider, display_name, provider_retains_data, input_price_per_mtok,
                                       output_price_per_mtok, currency, enabled)
                VALUES (?, ?, ?, FALSE, ?, ?, 'USD', ?)
                ON CONFLICT (id) DO UPDATE SET provider = EXCLUDED.provider,
                    input_price_per_mtok = EXCLUDED.input_price_per_mtok,
                    output_price_per_mtok = EXCLUDED.output_price_per_mtok, enabled = EXCLUDED.enabled""",
                id, provider, id, new BigDecimal(inputPrice), new BigDecimal(outputPrice), enabled);
    }

    /** A RUNNING PRODUCTION run of the given manifest (from src/test/resources/manifest) with a raw run token. */
    public LlmAgent runningAgent(String manifestFile) {
        var scenario = executionFixtures.liveWithManifest(manifestFile, List.of("bank_statement"),
                new BigDecimal("500.00"), new BigDecimal("1000.00"));
        String token = "rt-" + UUID.randomUUID();
        Run run = executionFixtures.run(scenario, RunStatus.RUNNING, "sbx-" + UUID.randomUUID(), token);
        run.setDeadlineAt(Instant.now().plusSeconds(600));
        return new LlmAgent(scenario, runs.save(run), token);
    }

    public void verifyDeveloperOrg(Org org) {
        jdbc.update("UPDATE org SET developer_verified_at = now() WHERE id = ?", org.getId());
    }

    /** {@code {used, reserved}} straight from the database. */
    public int[] tokens(UUID runId) {
        return jdbc.queryForObject("SELECT llm_tokens_used, llm_tokens_reserved FROM run WHERE id = ?",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)}, runId);
    }

    public void setTokens(UUID runId, int used, int reserved) {
        jdbc.update("UPDATE run SET llm_tokens_used = ?, llm_tokens_reserved = ? WHERE id = ?", used, reserved, runId);
    }

    /** Sets the run's mode and its LLM mock/replay configuration in one flush (the way WS6 profiles will). */
    public void configureRun(UUID runId, RunMode mode, RunLlmMode llmMode, UUID replayRunId, String mockJson) {
        Run run = runs.findById(runId).orElseThrow();
        run.setMode(mode);
        run.setLlmMode(llmMode);
        run.setReplayRunId(replayRunId);
        run.setLlmMockJson(mockJson);
        runs.saveAndFlush(run);
    }
}
