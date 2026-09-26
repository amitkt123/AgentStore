package com.agentstore.platform.data;

import com.agentstore.platform.catalog.AgentVersion;
import com.agentstore.platform.catalog.AgentVersionRepository;
import com.agentstore.platform.catalog.VersionStatus;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ResourceDecl;
import com.agentstore.platform.storage.ObjectStorage;
import com.fasterxml.jackson.databind.MappingIterator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

@Service
public class DatasetCheckService {

    private final DatasetRepository datasets;
    private final AgentVersionRepository versions;
    private final ManifestParser manifestParser;
    private final ObjectStorage storage;

    public DatasetCheckService(DatasetRepository datasets, AgentVersionRepository versions,
                               ManifestParser manifestParser, ObjectStorage storage) {
        this.datasets = datasets;
        this.versions = versions;
        this.manifestParser = manifestParser;
        this.storage = storage;
    }

    /** The endpoint's entry point: the dataset must be the caller's, the version LIVE, the resource a READ one. */
    public SchemaChecker.CheckResult check(UUID orgId, UUID datasetId, UUID agentVersionId, String resource) {
        Dataset dataset = datasets.findByIdAndOwnerOrgId(datasetId, orgId)
                .filter(d -> d.getDeletedAt() == null && d.getPurgedAt() == null)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Dataset not found"));
        AgentVersion version = versions.findById(agentVersionId)
                .filter(v -> v.getStatus() == VersionStatus.LIVE)
                .orElseThrow(() -> new DataApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Agent version not found"));
        Manifest manifest = manifestParser.parse(version.getManifestJson());
        return check(dataset, ResourceLookup.requireReadable(manifest, resource));
    }

    /** Checks a stored file against a resource declaration (null = untyped). Saves nothing. */
    public SchemaChecker.CheckResult check(Dataset dataset, ResourceDecl decl) {
        if (decl != null && decl.format().tabular() != dataset.getFormat().tabular()) {
            return SchemaChecker.CheckResult.formatMismatch("This resource expects "
                    + (decl.format().tabular() ? "rows of data (CSV or JSON)" : "a PDF document")
                    + " but the file is " + dataset.getFormat().wire());
        }
        if (!dataset.getFormat().tabular()) {
            return SchemaChecker.CheckResult.file();
        }
        try (InputStream in = storage.open(dataset.getRecordsKey());
             MappingIterator<Map<String, Object>> rows = Jsonl.readRows(in)) {
            return SchemaChecker.check(decl == null ? null : decl.schema(), dataset.columns(), rows);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
