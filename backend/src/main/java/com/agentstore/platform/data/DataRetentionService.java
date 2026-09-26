package com.agentstore.platform.data;

import com.agentstore.platform.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

/**
 * Hourly cleanup. A dataset whose grace ended (explicit delete: right away; unplugged: 30 days) is purged unless it is
 * bound again or an open run pins it: blobs deleted, keys nulled, {@code purged_at} set. The row stays as a tombstone
 * (name, size, sha) because run pins and traces refer to it; no buyer content survives. Blobs of DISCARDED outputs go
 * after 30 days. COMMITTED outputs are kept.
 */
@Service
public class DataRetentionService {

    static final Duration DISCARDED_OUTPUT_RETENTION = Duration.ofDays(30);
    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    public record Summary(int datasetsPurged, int outputBlobsDeleted) {
    }

    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final RunDatasetPinRepository pins;
    private final RunOutputRepository outputs;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public DataRetentionService(DatasetRepository datasets, DatasetBindingRepository bindings,
                                RunDatasetPinRepository pins, RunOutputRepository outputs, ObjectStorage storage,
                                TransactionTemplate tx) {
        this.datasets = datasets;
        this.bindings = bindings;
        this.pins = pins;
        this.outputs = outputs;
        this.storage = storage;
        this.tx = tx;
    }

    public Summary purgeOnce() {
        return purgeOnce(Instant.now());
    }

    public Summary purgeOnce(Instant now) {
        int purged = 0;
        for (Dataset due : datasets.findDueForPurge(now)) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> purge(due.getId(), now)))) {
                    purged++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not purge dataset {}", due.getId(), e);   // the next run retries it
            }
        }
        int deleted = 0;
        for (RunOutput output : outputs.findByStatusAndCreatedAtBeforeAndStorageKeyIsNotNull(OutputStatus.DISCARDED,
                now.minus(DISCARDED_OUTPUT_RETENTION))) {
            try {
                storage.deleteQuietly(output.getStorageKey());
                output.setStorageKey(null);
                outputs.save(output);
                deleted++;
            } catch (RuntimeException e) {
                log.warn("Could not clear discarded output {}", output.getId(), e);
            }
        }
        return new Summary(purged, deleted);
    }

    /** Under the dataset's row lock, so a bind or delete cannot interleave. */
    private boolean purge(java.util.UUID datasetId, Instant now) {
        Dataset dataset = datasets.findByIdForUpdate(datasetId).orElse(null);
        if (dataset == null || dataset.getPurgedAt() != null || dataset.getPurgeAfter() == null
                || dataset.getPurgeAfter().isAfter(now)) {
            return false;
        }
        if (bindings.existsByDatasetId(datasetId) || pins.pinnedByOpenRun(datasetId)) {
            return false;
        }
        if (dataset.getStorageKey() != null) {
            storage.deleteQuietly(dataset.getStorageKey());
        }
        if (dataset.getRecordsKey() != null) {
            storage.deleteQuietly(dataset.getRecordsKey());
        }
        dataset.setStorageKey(null);
        dataset.setRecordsKey(null);
        dataset.setPurgedAt(Instant.now());
        datasets.save(dataset);
        return true;
    }
}
