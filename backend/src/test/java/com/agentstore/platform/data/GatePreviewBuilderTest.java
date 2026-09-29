package com.agentstore.platform.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.ExecutionFixtures;
import com.agentstore.platform.execution.RunStatus;
import com.agentstore.platform.manifest.ApprovalGateDecl;
import com.agentstore.platform.storage.ObjectStorage;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GatePreviewBuilderTest extends AbstractStorageIntegrationTest {

    @Autowired
    GatePreviewBuilder builder;
    @Autowired
    RunOutputRepository outputs;
    @Autowired
    ObjectStorage storage;
    @Autowired
    ExecutionFixtures fixtures;

    private UUID newRun() {
        var s = fixtures.liveTierA(new BigDecimal("500.00"), new BigDecimal("1000.00"));
        return fixtures.run(s, RunStatus.RUNNING, "sbx-" + System.nanoTime(), "tok-" + System.nanoTime()).getId();
    }

    private void stage(UUID runId, String resource, String... jsonLines) {
        String key = "outputs/" + runId + "/" + resource + ".jsonl";
        byte[] bytes = (String.join("\n", jsonLines) + "\n").getBytes(StandardCharsets.UTF_8);
        storage.put(key, bytes);
        outputs.save(new RunOutput(UUID.randomUUID(), runId, resource, OutputStatus.STAGED, jsonLines.length, bytes.length, key));
    }

    @Test
    void firstTwentyRowsCutToColumnsWithTotals() throws Exception {
        UUID runId = newRun();
        String[] lines = IntStream.rangeClosed(1, 30)
                .mapToObj(i -> "{\"date\":\"2026-01-%02d\",\"account\":\"A%d\",\"amount\":%d.50}".formatted(i, i, i))
                .toArray(String[]::new);
        stage(runId, "journal_entries", lines);
        String json = builder.build(runId, List.of("journal_entries"),
                new ApprovalGateDecl.Preview(List.of("date", "amount"), "amount"));
        JsonNode p = Jsonl.MAPPER.readTree(json);
        assertThat(p.get("rows")).hasSize(20);
        assertThat(p.get("rows").get(0).fieldNames()).toIterable().containsExactly("date", "amount");
        assertThat(p.get("totalRows").asInt()).isEqualTo(30);
        assertThat(p.get("total").decimalValue()).isEqualByComparingTo("480.00");   // 1.50..30.50: 465 + 30 * 0.50
    }

    @Test
    void noConfigMeansAllColumnsAndNoTotal() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"date\":\"2026-01-01\",\"amount\":5}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("journal_entries"), null));
        assertThat(p.has("total")).isFalse();
        assertThat(p.has("columns")).isFalse();
        assertThat(p.get("rows").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("date", "amount");
    }

    @Test
    void nothingStagedIsNull() {
        assertThat(builder.build(newRun(), List.of("journal_entries"), null)).isNull();
    }

    @Test
    void nonNumericAndMissingTotalFieldValuesAreSkipped() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"amount\":\"n/a\"}", "{\"other\":1}", "{\"amount\":2}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("journal_entries"),
                new ApprovalGateDecl.Preview(null, "amount")));
        assertThat(p.get("total").decimalValue()).isEqualByComparingTo("2");
        assertThat(p.get("totalRows").asInt()).isEqualTo(3);
    }

    @Test
    void unknownColumnIsIgnoredNotAnError() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"date\":\"2026-01-01\",\"amount\":5}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("journal_entries"),
                new ApprovalGateDecl.Preview(List.of("nope", "date"), null)));
        assertThat(p.get("rows").get(0).fieldNames()).toIterable().containsExactly("date");
    }

    @Test
    void discardedAndCommittedOutputsAreNotPreviewed() {
        UUID runId = newRun();
        String key = "outputs/" + runId + "/x.jsonl";
        storage.put(key, "{\"a\":1}\n".getBytes(StandardCharsets.UTF_8));
        outputs.save(new RunOutput(UUID.randomUUID(), runId, "journal_entries", OutputStatus.DISCARDED, 1, 8, key));
        outputs.save(new RunOutput(UUID.randomUUID(), runId, "journal_entries", OutputStatus.COMMITTED, 1, 8, key));
        assertThat(builder.build(runId, List.of("journal_entries"), null)).isNull();
    }

    @Test
    void noAffectedResourcesMeansNoPreviewBecauseNothingWouldBeCommitted() {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"a\":1}");
        // OutputCommitService commits nothing for an empty list, so the buyer must not be shown a preview of everything
        assertThat(builder.build(runId, List.of(), null)).isNull();
        assertThat(builder.build(runId, null, null)).isNull();
    }

    @Test
    void onlyOutputsForAffectedResourcesAreUsed() throws Exception {
        UUID runId = newRun();
        stage(runId, "journal_entries", "{\"a\":1}", "{\"a\":2}");
        stage(runId, "vendors", "{\"a\":9}");
        JsonNode p = Jsonl.MAPPER.readTree(builder.build(runId, List.of("vendors"), null));
        assertThat(p.get("totalRows").asInt()).isEqualTo(1);
    }
}
