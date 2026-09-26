package com.agentstore.platform.build;

import com.agentstore.platform.catalog.*;
import com.agentstore.platform.identity.JwtService;
import com.agentstore.platform.manifest.BundleManifestRules;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.ManifestParser;
import com.agentstore.platform.manifest.ManifestValidator;
import com.agentstore.platform.storage.ObjectStorage;
import com.agentstore.platform.storage.StorageException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bundle upload and finalize (spec §4 step 1). Validation reads the stored object outside any transaction; only the
 * final bookkeeping runs in one, under a row lock on the bundle, so a retried or concurrent finalize queues one build.
 */
@Service
public class BundleService {

    public static final long MAX_BUNDLE_BYTES = 100L * 1024 * 1024;
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(15);

    private final AgentRepository agentRepository;
    private final AgentBundleRepository bundleRepository;
    private final AgentVersionRepository versionRepository;
    private final TemplateBuildRepository buildRepository;
    private final ObjectStorage storage;
    private final BundleInspector inspector;
    private final ManifestValidator validator;
    private final ManifestParser parser;
    private final TransactionTemplate tx;
    private final String backend;

    public BundleService(AgentRepository agentRepository, AgentBundleRepository bundleRepository,
                         AgentVersionRepository versionRepository, TemplateBuildRepository buildRepository,
                         ObjectStorage storage, BundleInspector inspector, ManifestValidator validator,
                         ManifestParser parser, PlatformTransactionManager transactionManager,
                         @Value("${agentstore.runner.backend:e2b}") String backend) {
        this.agentRepository = agentRepository;
        this.bundleRepository = bundleRepository;
        this.versionRepository = versionRepository;
        this.buildRepository = buildRepository;
        this.storage = storage;
        this.inspector = inspector;
        this.validator = validator;
        this.parser = parser;
        this.tx = new TransactionTemplate(transactionManager);
        this.backend = backend;
    }

    public BundleUploadResponse createUpload(UUID agentId, String sha256, long sizeBytes, JwtService.Claims caller) {
        Agent agent = ownedAgent(agentId, caller);
        if (sizeBytes > MAX_BUNDLE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Bundle is larger than 100 MB");
        }
        UUID id = UUID.randomUUID();
        String key = "bundles/" + agentId + "/" + id + ".zip";
        bundleRepository.save(new AgentBundle(id, agent, caller.userId(), key, sha256.toLowerCase(Locale.ROOT),
                sizeBytes, BundleStatus.UPLOADING));
        return new BundleUploadResponse(id, storage.presignPut(key, UPLOAD_URL_TTL), Instant.now().plus(UPLOAD_URL_TTL));
    }

    public FinalizeBundleResponse finalizeBundle(UUID agentId, UUID bundleId, String sha256, JwtService.Claims caller) {
        ownedAgent(agentId, caller);
        AgentBundle bundle = bundleRepository.findById(bundleId)
                .filter(b -> b.getAgent().getId().equals(agentId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Bundle not found"));
        if (bundle.getStatus() == BundleStatus.UPLOADED) {
            return existingResult(bundle);
        }
        if (bundle.getStatus() == BundleStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This bundle was rejected; upload a new one");
        }
        if (!bundle.getSha256().equalsIgnoreCase(sha256)) {
            throw reject(bundle, "sha256 does not match the one declared when the upload was created");
        }
        long size = storage.size(bundle.getStorageKey())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "The bundle has not been uploaded yet"));
        if (size != bundle.getSizeBytes() || size > MAX_BUNDLE_BYTES) {
            throw reject(bundle, "Uploaded size " + size + " bytes does not match the declared " + bundle.getSizeBytes()
                    + " bytes (limit 100 MB)");
        }
        Manifest manifest;
        String manifestJson;
        try (InputStream in = storage.open(bundle.getStorageKey())) {
            BundleInspector.Inspection inspection = inspector.inspect(in);
            manifestJson = inspection.manifestJson();
            manifest = check(inspection, bundle);
        } catch (BundleRejectedException e) {
            throw reject(bundle, e.getMessage());
        } catch (IOException e) {
            throw new StorageException("Could not read bundle " + bundleId, e);
        }
        String json = manifestJson;
        return tx.execute(status -> record(agentId, bundleId, manifest, json));
    }

    private Manifest check(BundleInspector.Inspection inspection, AgentBundle bundle) {
        if (!inspection.sha256().equalsIgnoreCase(bundle.getSha256())) {
            throw new BundleRejectedException("The uploaded bytes do not match the declared sha256");
        }
        var validation = validator.validate(inspection.manifestJson());
        if (!validation.valid()) {
            throw new BundleRejectedException("manifest.json is invalid: " + String.join("; ", validation.errors()));
        }
        Manifest manifest = parser.parse(inspection.manifestJson());
        List<String> errors = BundleManifestRules.check(manifest);
        if (!errors.isEmpty()) {
            throw new BundleRejectedException("manifest.json is invalid: " + String.join("; ", errors));
        }
        if (!inspection.files().contains(manifest.entrypoint())) {
            throw new BundleRejectedException("Entrypoint " + manifest.entrypoint() + " is not in the bundle");
        }
        versionRepository.findByAgentIdAndVersionNumber(bundle.getAgent().getId(), manifest.version())
                .filter(v -> v.getStatus() != VersionStatus.DRAFT)
                .ifPresent(v -> {
                    throw new BundleRejectedException("Version " + manifest.version() + " is already " + v.getStatus()
                            + "; bump the version in manifest.json");
                });
        return manifest;
    }

    /**
     * Lock order is always agent row, then bundle row. The agent lock serializes every finalize of one agent, so two
     * bundles for the same version (new or existing DRAFT) cannot both read the old state, both queue a build, or
     * overwrite each other's version update. The bundle lock keeps a retried finalize of one bundle idempotent.
     */
    private FinalizeBundleResponse record(UUID agentId, UUID bundleId, Manifest manifest, String manifestJson) {
        agentRepository.findByIdForUpdate(agentId).orElseThrow();
        AgentBundle bundle = bundleRepository.findByIdForUpdate(bundleId).orElseThrow();
        if (bundle.getStatus() == BundleStatus.UPLOADED) {
            return existingResult(bundle);   // a concurrent finalize won the race
        }
        if (bundle.getStatus() != BundleStatus.UPLOADING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This bundle was rejected; upload a new one");
        }
        AgentVersion version = versionRepository.findByAgentIdAndVersionNumber(bundle.getAgent().getId(), manifest.version())
                .orElseGet(() -> new AgentVersion(null, bundle.getAgent(), manifest.version(), manifestJson, null,
                        VersionStatus.DRAFT, null, null));
        if (version.getStatus() != VersionStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Version " + manifest.version() + " is no longer a draft");
        }
        if (version.getId() != null) {
            supersedeQueuedBuilds(version.getId());
        }
        version.setManifestJson(manifestJson);
        version.setBundle(bundle);
        version.setImageRef(null);
        version.setBuildStatus(BuildStatus.QUEUED);
        version = versionRepository.save(version);
        TemplateBuild build = buildRepository.save(new TemplateBuild(version, bundle, backend, manifest.runtime(),
                manifest.entrypoint()));
        build.setLogStorageKey("builds/" + build.getId() + ".log");
        bundle.setStatus(BundleStatus.UPLOADED);
        return new FinalizeBundleResponse(bundle.getId(), version.getId(), version.getVersionNumber(), build.getId(),
                build.getStatus());
    }

    private void supersedeQueuedBuilds(UUID versionId) {
        for (TemplateBuild old : buildRepository.findByAgentVersionIdAndStatus(versionId, BuildStatus.QUEUED)) {
            old.setStatus(BuildStatus.FAILED);
            old.setFailureCode(BuildFailureCodes.SUPERSEDED);
            old.setFailureReason("A newer bundle was pushed for this version");
            old.setFinishedAt(Instant.now());
        }
    }

    private FinalizeBundleResponse existingResult(AgentBundle bundle) {
        TemplateBuild build = buildRepository.findFirstByBundleIdOrderByQueuedAtDesc(bundle.getId())
                .orElseThrow(() -> new IllegalStateException("Uploaded bundle " + bundle.getId() + " has no build"));
        AgentVersion version = build.getAgentVersion();
        return new FinalizeBundleResponse(bundle.getId(), version.getId(), version.getVersionNumber(), build.getId(),
                build.getStatus());
    }

    /**
     * Marks the bundle REJECTED and deletes its object, but only if this call is the one that moved it out of UPLOADING:
     * a concurrent finalize that already accepted the bundle must keep its object.
     */
    private BundleRejectedException reject(AgentBundle bundle, String reason) {
        AtomicBoolean rejected = new AtomicBoolean();
        tx.executeWithoutResult(status -> bundleRepository.findByIdForUpdate(bundle.getId())
                .filter(b -> b.getStatus() == BundleStatus.UPLOADING)
                .ifPresent(b -> {
                    b.setStatus(BundleStatus.REJECTED);
                    rejected.set(true);
                }));
        if (rejected.get()) {
            storage.deleteQuietly(bundle.getStorageKey());
        }
        return new BundleRejectedException(reason);
    }

    private Agent ownedAgent(UUID agentId, JwtService.Claims caller) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found"));
        if (!agent.getDeveloperOrg().getId().equals(caller.orgId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent not owned by caller's org");
        }
        return agent;
    }
}
