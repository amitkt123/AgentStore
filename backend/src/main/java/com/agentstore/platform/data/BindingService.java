package com.agentstore.platform.data;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.ConnectionRepository;
import com.agentstore.platform.execution.ConnectionStatus;
import com.agentstore.platform.manifest.DataFormat;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class BindingService {

    /** How long an unplugged file lives before the retention job may purge it. */
    static final Duration UNPLUG_GRACE = Duration.ofDays(30);

    public record BindingResponse(String resource, UUID datasetId, String displayName, DataFormat format,
                                  Long rowCount, Instant boundAt) {
    }

    private final ConnectionRepository connections;
    private final DatasetRepository datasets;
    private final DatasetBindingRepository bindings;
    private final DatasetCheckService checks;
    private final ManifestParser manifestParser;

    public BindingService(ConnectionRepository connections, DatasetRepository datasets,
                          DatasetBindingRepository bindings, DatasetCheckService checks, ManifestParser manifestParser) {
        this.connections = connections;
        this.datasets = datasets;
        this.bindings = bindings;
        this.checks = checks;
        this.manifestParser = manifestParser;
    }

    @Transactional
    public BindingResponse bind(UUID orgId, UUID userId, UUID connectionId, String resource, UUID datasetId) {
        Connection connection = ownedConnection(orgId, connectionId);
        if (connection.getStatus() != ConnectionStatus.ACTIVE) {
            throw new DataApiException(HttpStatus.CONFLICT, "CONNECTION_NOT_ACTIVE", "Connection is not active");
        }
        Dataset dataset = datasets.findByIdForUpdate(datasetId)
                .filter(d -> d.getOwnerOrgId().equals(orgId) && d.getDeletedAt() == null && d.getPurgedAt() == null
                        && d.getPurpose() == DatasetPurpose.BUYER_DATA)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Dataset not found"));
        AgentVersion version = connection.getAgentVersion();
        if (version == null) {
            throw new DataApiException(HttpStatus.CONFLICT, "NO_AGENT_VERSION",
                    "Connection is not pinned to an agent version");
        }
        Manifest manifest = manifestParser.parse(version.getManifestJson());
        ResourceDecl decl = ResourceLookup.requireReadable(manifest, resource);
        if (!connection.getGrantedScopes().contains(resource)) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RESOURCE_NOT_GRANTED",
                    "This connection does not grant access to '" + resource + "'");
        }
        SchemaChecker.CheckResult result = checks.check(dataset, decl);
        if (!result.ok()) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DATASET_CHECK_FAILED",
                    "The file does not match what the agent expects", Map.of("checkResult", result));
        }
        String checkJson = json(result);
        DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.CONNECTION,
                connectionId, resource).orElse(null);
        UUID replaced = null;
        if (binding == null) {
            binding = new DatasetBinding(BindingOwnerType.CONNECTION, connectionId, resource, datasetId, checkJson, userId);
        } else {
            if (!binding.getDatasetId().equals(datasetId)) {
                replaced = binding.getDatasetId();
            }
            binding.setDatasetId(datasetId);
            binding.setCheckResult(checkJson);
            binding.setBoundByUserId(userId);
            binding.setBoundAt(Instant.now());
        }
        bindings.saveAndFlush(binding);
        dataset.setPurgeAfter(null);
        if (replaced != null) {
            release(replaced);
        }
        return response(binding, dataset);
    }

    @Transactional
    public void unbind(UUID orgId, UUID connectionId, String resource) {
        ownedConnection(orgId, connectionId);
        DatasetBinding binding = bindings.findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType.CONNECTION,
                connectionId, resource).orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                "Nothing is bound to '" + resource + "'"));
        bindings.delete(binding);
        bindings.flush();
        release(binding.getDatasetId());
    }

    @Transactional(readOnly = true)
    public List<BindingResponse> list(UUID orgId, UUID connectionId) {
        ownedConnection(orgId, connectionId);
        return bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.CONNECTION, connectionId).stream()
                .map(b -> response(b, datasets.findById(b.getDatasetId()).orElseThrow())).toList();
    }

    /** For revoking a connection: unplugs everything and starts the 30-day clock for files no longer bound anywhere. */
    @Transactional
    public void unbindAll(UUID connectionId) {
        List<DatasetBinding> all = bindings.findByOwnerTypeAndOwnerId(BindingOwnerType.CONNECTION, connectionId);
        bindings.deleteAll(all);
        bindings.flush();
        all.stream().map(DatasetBinding::getDatasetId).distinct().forEach(this::release);
    }

    /** A file no longer bound anywhere gets the grace period (unless it is already being deleted). */
    private void release(UUID datasetId) {
        if (bindings.existsByDatasetId(datasetId)) {
            return;
        }
        datasets.findByIdForUpdate(datasetId).filter(d -> d.getDeletedAt() == null && d.getPurgedAt() == null)
                .ifPresent(d -> d.setPurgeAfter(Instant.now().plus(UNPLUG_GRACE)));
    }

    private Connection ownedConnection(UUID orgId, UUID connectionId) {
        Connection connection = connections.findById(connectionId)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Connection not found"));
        if (!connection.getBuyerOrg().getId().equals(orgId)) {
            throw new DataApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Connection not owned by caller's org");
        }
        return connection;
    }

    private static BindingResponse response(DatasetBinding b, Dataset d) {
        return new BindingResponse(b.getResourceName(), d.getId(), d.getDisplayName(), d.getFormat(), d.getRowCount(),
                b.getBoundAt());
    }

    private static String json(Object value) {
        try {
            return Jsonl.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
