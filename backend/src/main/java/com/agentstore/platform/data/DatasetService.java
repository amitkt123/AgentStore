package com.agentstore.platform.data;

import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.ConnectionRepository;
import com.agentstore.platform.identity.Org;
import com.agentstore.platform.identity.OrgRepository;
import com.agentstore.platform.identity.User;
import com.agentstore.platform.identity.UserRepository;
import com.agentstore.platform.manifest.DataFormat;
import com.agentstore.platform.storage.ObjectStorage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class DatasetService {

    public record StorageResponse(long usedBytes, long limitBytes) {}

    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final RunDatasetPinRepository pins;
    private final OrgRepository orgs;
    private final UserRepository users;
    private final ConnectionRepository connections;
    private final ObjectStorage storage;
    private final TransactionTemplate tx;

    public DatasetService(DatasetRepository datasets, DatasetBindingRepository bindings, RunDatasetPinRepository pins,
                          OrgRepository orgs, UserRepository users, ConnectionRepository connections,
                          ObjectStorage storage, TransactionTemplate tx) {
        this.datasets = datasets;
        this.bindings = bindings;
        this.pins = pins;
        this.orgs = orgs;
        this.users = users;
        this.connections = connections;
        this.storage = storage;
        this.tx = tx;
    }

    /**
     * Streams the upload to a temp file (hashing as it goes), normalizes it, writes the blobs, then registers the row
     * under the org's row lock so two uploads cannot both slip under the quota. Blobs are removed if registering fails.
     */
    /** {@code purpose} defaults to BUYER_DATA under the workspace storage limit; a sandbox fixture passes FIXTURE and its own quota check. */
    public Dataset upload(UUID orgId, UUID userId, String filename, InputStream content, String displayName,
                          String sourceLabel) {
        return upload(orgId, userId, filename, content, displayName, sourceLabel, DatasetPurpose.BUYER_DATA, null);
    }

    /** {@code quota}, when given, replaces the workspace storage-limit check (the sandbox fixture quota). */
    public Dataset upload(UUID orgId, UUID userId, String filename, InputStream content, String displayName,
                          String sourceLabel, DatasetPurpose purpose, UploadQuota quota) {
        if (displayName == null || displayName.isBlank()) {
            throw new DataApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "displayName must not be blank");
        }
        Path dir = null;
        try {
            dir = Files.createTempDirectory("dataset-upload");
            Path original = dir.resolve("original");
            MessageDigest digest = sha256();
            try (DigestInputStream hashing = new DigestInputStream(content, digest)) {
                Files.copy(hashing, original, StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(original);
            DataFormat format = FormatDetector.detect(filename, original).orElseThrow(() -> new DataApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FORMAT", "Upload a CSV, JSON or PDF file"));

            Path records = dir.resolve("records.jsonl");
            Normalized normalized = null;
            long recordsSize = 0;
            if (format == DataFormat.CSV) {
                normalized = CsvNormalizer.normalize(original, records);
            } else if (format == DataFormat.JSON) {
                normalized = JsonNormalizer.normalize(original, records);
            }
            if (normalized != null) {
                recordsSize = Files.size(records);
            }

            String base = "datasets/" + orgId + "/" + UUID.randomUUID() + "/";
            String originalKey = base + "original";
            String recordsKey = normalized == null ? null : base + "records.jsonl";
            storage.putFile(originalKey, original);
            if (recordsKey != null) {
                storage.putFile(recordsKey, records);
            }
            try {
                Dataset dataset = new Dataset();
                dataset.setOwnerOrgId(orgId);
                dataset.setPurpose(purpose);
                dataset.setDisplayName(displayName.strip());
                dataset.setSourceLabel(sourceLabel == null || sourceLabel.isBlank() ? null : sourceLabel.strip());
                dataset.setUploadedByUserId(userId);
                dataset.setFormat(format);
                dataset.setStorageKey(originalKey);
                dataset.setRecordsKey(recordsKey);
                dataset.setSha256(HexFormat.of().formatHex(digest.digest()));
                dataset.setSizeBytes(size);
                dataset.setRecordsSizeBytes(recordsSize);
                dataset.setRowCount(normalized == null ? null : normalized.rowCount());
                dataset.setColumns(normalized == null ? List.of() : normalized.columns());
                long incoming = size + recordsSize;
                return tx.execute(status -> {
                    if (quota != null) {
                        quota.check(orgId, incoming);
                    } else {
                        Org org = orgs.findByIdForUpdate(orgId).orElseThrow();
                        if (datasets.usedBytes(orgId) + incoming > org.getStorageLimitBytes()) {
                            throw new DataApiException(HttpStatus.PAYLOAD_TOO_LARGE, "QUOTA_EXCEEDED",
                                    "This upload would take the workspace over its storage limit");
                        }
                    }
                    return datasets.save(dataset);
                });
            } catch (RuntimeException e) {
                storage.deleteQuietly(originalKey);
                if (recordsKey != null) {
                    storage.deleteQuietly(recordsKey);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteQuietly(dir);
        }
    }

    public List<DatasetResponse> list(UUID orgId, boolean withUsage) {
        return list(orgId, DatasetPurpose.BUYER_DATA, withUsage);
    }

    /** A developer's uploaded fixtures: same ingestion path, purpose FIXTURE, never usage-annotated. */
    public List<DatasetResponse> listFixtures(UUID orgId) {
        return list(orgId, DatasetPurpose.FIXTURE, false);
    }

    private List<DatasetResponse> list(UUID orgId, DatasetPurpose purpose, boolean withUsage) {
        List<Dataset> rows = datasets.findByOwnerOrgIdAndPurposeAndDeletedAtIsNullOrderByCreatedAtDesc(orgId, purpose);
        Map<UUID, List<DatasetResponse.Usage>> usage = withUsage ? usageByDataset(rows) : Map.of();
        Map<UUID, String> uploaders = new HashMap<>();
        return rows.stream().map(d -> DatasetResponse.from(d,
                uploaders.computeIfAbsent(d.getUploadedByUserId() == null ? new UUID(0, 0) : d.getUploadedByUserId(),
                        id -> users.findById(id).map(User::getFullName).orElse(null)),
                withUsage ? usage.getOrDefault(d.getId(), List.of()) : null)).toList();
    }

    private Map<UUID, List<DatasetResponse.Usage>> usageByDataset(List<Dataset> rows) {
        List<DatasetBinding> all = bindings.findByDatasetIdIn(rows.stream().map(Dataset::getId).toList()).stream()
                .filter(b -> b.getOwnerType() == BindingOwnerType.CONNECTION).toList();
        Map<UUID, Connection> byId = connections.findAllById(all.stream().map(DatasetBinding::getOwnerId).toList())
                .stream().collect(Collectors.toMap(Connection::getId, Function.identity()));
        return all.stream().filter(b -> byId.containsKey(b.getOwnerId())).collect(Collectors.groupingBy(
                DatasetBinding::getDatasetId, Collectors.mapping(b -> {
                    Connection c = byId.get(b.getOwnerId());
                    return new DatasetResponse.Usage(c.getId(), b.getResourceName(), c.getAgent().getName(),
                            c.getAgentVersion() == null ? null : c.getAgentVersion().getVersionNumber(), "READ");
                }, Collectors.toList())));
    }

    /**
     * Unbinds everywhere and schedules the purge for right now (the retention job removes the blobs within about an
     * hour): a 30-day window on an explicit delete would be a privacy liability.
     */
    @Transactional
    public void delete(UUID orgId, UUID datasetId) {
        delete(orgId, datasetId, d -> true);
    }

    /** As {@link #delete(UUID, UUID)}, but only for a fixture (the developer-fixtures endpoint's own dataset). */
    @Transactional
    public void deleteFixture(UUID orgId, UUID datasetId) {
        delete(orgId, datasetId, d -> d.getPurpose() == DatasetPurpose.FIXTURE);
    }

    private void delete(UUID orgId, UUID datasetId, java.util.function.Predicate<Dataset> purposeCheck) {
        Dataset dataset = datasets.findByIdForUpdate(datasetId)
                .filter(d -> d.getOwnerOrgId().equals(orgId) && d.getDeletedAt() == null && d.getPurgedAt() == null
                        && purposeCheck.test(d))
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Dataset not found"));
        if (pins.pinnedByOpenRun(datasetId)) {
            throw new DataApiException(HttpStatus.CONFLICT, "DATASET_IN_USE",
                    "A run that has not finished is reading this file; delete it once the run ends");
        }
        bindings.deleteByDatasetId(datasetId);
        Instant now = Instant.now();
        dataset.setDeletedAt(now);
        dataset.setPurgeAfter(now);
    }

    /** Lets a caller (the sandbox fixture quota) replace the workspace storage-limit check. */
    public interface UploadQuota {
        void check(UUID orgId, long incomingBytes);
    }

    public StorageResponse storage(UUID orgId) {
        Org org = orgs.findById(orgId).orElseThrow();
        return new StorageResponse(datasets.usedBytes(orgId), org.getStorageLimitBytes());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a temp file left in the OS temp dir is not worth failing the request over
        }
    }
}
