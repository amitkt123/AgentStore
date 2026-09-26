package com.agentstore.platform.data;

import com.agentstore.platform.AbstractStorageIntegrationTest;
import com.agentstore.platform.execution.RunLifecycleService;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.runtime.RunPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DataRetentionTest extends AbstractStorageIntegrationTest {

    @Autowired
    DataFixtures fx;
    @Autowired
    DataRetentionService retention;
    @Autowired
    DatasetService datasetService;
    @Autowired
    DatasetRepository datasetRepository;
    @Autowired
    RunOutputRepository outputRepository;
    @Autowired
    RunOutputWriter writer;
    @Autowired
    RunRepository runRepository;
    @Autowired
    RunLifecycleService lifecycle;
    @Autowired
    ManifestParser parser;

    @Test
    void anExplicitlyDeletedFileIsPurgedToATombstone() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        String original = d.getStorageKey();
        String records = d.getRecordsKey();
        datasetService.delete(s.buyer().getId(), d.getId());

        DataRetentionService.Summary summary = retention.purgeOnce(Instant.now().plusSeconds(1));

        assertThat(summary.datasetsPurged()).isGreaterThanOrEqualTo(1);
        Dataset after = datasetRepository.findById(d.getId()).orElseThrow();       // the row is a tombstone, not deleted
        assertThat(after.getPurgedAt()).isNotNull();
        assertThat(after.getStorageKey()).isNull();
        assertThat(after.getRecordsKey()).isNull();
        assertThat(after.getDisplayName()).isEqualTo("a.csv");
        assertThat(after.getSha256()).isEqualTo(d.getSha256());
        assertThat(storage.size(original)).isEmpty();
        assertThat(storage.size(records)).isEmpty();
        assertThat(datasetRepository.usedBytes(s.buyer().getId())).isZero();
    }

    @Test
    void anUnpluggedFileWaitsThirtyDays() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        fx.bind(s.connection(), "bank_statement", d);
        fx.unbindAll(s.connection());

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(29)));
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNull();

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(31)));
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNotNull();
    }

    @Test
    void aFileAnOpenRunPinsIsNeverPurgedEvenWhenDue() {
        var agent = fx.runningAgent();
        Dataset d = fx.upload(agent.scenario().buyer(), "a.csv", DataFixtures.CSV);
        fx.pin(agent.run(), "bank_statement", d);
        d.setPurgeAfter(Instant.now().minusSeconds(60));
        datasetRepository.save(d);

        retention.purgeOnce(Instant.now());
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNull();
        assertThat(storage.size(d.getRecordsKey())).isPresent();

        lifecycle.complete(agent.run().getId(), Map.of());
        retention.purgeOnce(Instant.now());
        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNotNull();
    }

    @Test
    void aFileThatIsStillBoundIsNeverPurged() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        fx.bind(s.connection(), "bank_statement", d);
        d.setPurgeAfter(Instant.now().minusSeconds(60));     // stale timestamp; the binding wins
        datasetRepository.save(d);

        retention.purgeOnce(Instant.now());

        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isNull();
    }

    @Test
    void discardedOutputBlobsAreDeletedAfterThirtyDaysAndCommittedOnesNever() {
        var agent = fx.runningAgent();
        var principal = RunPrincipal.forRun(runRepository.findById(agent.run().getId()).orElseThrow(), parser);
        writer.write(principal, "journal_entries", List.of(Map.of("bank_line", "l", "amount", 1)), true);   // becomes DISCARDED
        writer.write(principal, "audit_log", List.of(Map.of("event", "e")), false);                          // COMMITTED
        lifecycle.complete(agent.run().getId(), Map.of());
        List<RunOutput> outputs = outputRepository.findByRunIdOrderByCreatedAtAsc(agent.run().getId());
        RunOutput discarded = outputs.stream().filter(o -> o.getStatus() == OutputStatus.DISCARDED).findFirst().orElseThrow();
        RunOutput committed = outputs.stream().filter(o -> o.getStatus() == OutputStatus.COMMITTED).findFirst().orElseThrow();
        String discardedKey = discarded.getStorageKey();

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(29)));
        assertThat(storage.size(discardedKey)).isPresent();

        retention.purgeOnce(Instant.now().plus(Duration.ofDays(31)));
        assertThat(storage.size(discardedKey)).isEmpty();
        assertThat(outputRepository.findById(discarded.getId()).orElseThrow().getStorageKey()).isNull();
        assertThat(outputRepository.findById(committed.getId()).orElseThrow().getStorageKey()).isNotNull();
        assertThat(storage.size(committed.getStorageKey())).isPresent();
    }

    @Test
    void purgeIsIdempotent() {
        var s = fx.scenario();
        Dataset d = fx.upload(s.buyer(), "a.csv", DataFixtures.CSV);
        datasetService.delete(s.buyer().getId(), d.getId());

        retention.purgeOnce(Instant.now().plusSeconds(1));
        Instant firstPurge = datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt();
        retention.purgeOnce(Instant.now().plusSeconds(2));

        assertThat(datasetRepository.findById(d.getId()).orElseThrow().getPurgedAt()).isEqualTo(firstPurge);
    }
}
