package com.agentstore.platform.data;

import com.agentstore.platform.execution.Connection;
import com.agentstore.platform.execution.Run;
import com.agentstore.platform.execution.RunMode;
import com.agentstore.platform.execution.RunRepository;
import com.agentstore.platform.manifest.Manifest;
import com.agentstore.platform.manifest.Scope;
import com.agentstore.platform.manifest.ScopeType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Called from the run trigger: resolves what each granted READ resource is bound to and fixes it for the run. A
 * declared, granted READ resource with no usable binding blocks the trigger (there are no optional resources in v1);
 * a manifest without {@code resources} is untyped, so its bindings are pinned when present and never demanded.
 */
@Service
public class BindingResolver {

    public record ResolvedPin(String resource, UUID datasetId, String sha256) {
    }

    private final DatasetBindingRepository bindings;
    private final DatasetRepository datasets;
    private final RunDatasetPinRepository pins;
    private final RunRepository runs;

    public BindingResolver(DatasetBindingRepository bindings, DatasetRepository datasets, RunDatasetPinRepository pins,
                           RunRepository runs) {
        this.bindings = bindings;
        this.datasets = datasets;
        this.pins = pins;
        this.runs = runs;
    }

    @Transactional(readOnly = true)
    public List<ResolvedPin> resolve(Connection connection, Manifest manifest, RunMode mode) {
        Set<String> granted = new HashSet<>(connection.getGrantedScopes());
        Set<String> resources = new LinkedHashSet<>();
        for (Scope scope : manifest.scopes()) {
            if (scope.type() == ScopeType.READ && granted.contains(scope.resource())) {
                resources.add(scope.resource());
            }
        }
        return resolveResources(BindingOwnerType.CONNECTION, connection.getId(), resources, manifest, mode);
    }

    /** As {@link #resolve}, but for a sandbox profile: every declared READ resource, not just granted ones. */
    @Transactional(readOnly = true)
    public List<ResolvedPin> resolveForProfile(UUID profileId, Manifest manifest, RunMode mode) {
        Set<String> resources = new LinkedHashSet<>();
        for (Scope scope : manifest.scopes()) {
            if (scope.type() == ScopeType.READ) {
                resources.add(scope.resource());
            }
        }
        return resolveResources(BindingOwnerType.SANDBOX_PROFILE, profileId, resources, manifest, mode);
    }

    private List<ResolvedPin> resolveResources(BindingOwnerType ownerType, UUID ownerId, Set<String> resources,
                                               Manifest manifest, RunMode mode) {
        Map<String, DatasetBinding> bound = new HashMap<>();
        for (DatasetBinding binding : bindings.findByOwnerTypeAndOwnerId(ownerType, ownerId)) {
            bound.put(binding.getResourceName(), binding);
        }
        List<ResolvedPin> resolved = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String resource : resources) {
            Dataset dataset = java.util.Optional.ofNullable(bound.get(resource))
                    .flatMap(b -> datasets.findById(b.getDatasetId()))
                    .filter(d -> d.getPurgedAt() == null && d.getDeletedAt() == null
                            && DataAccessRules.allows(mode, d.getPurpose()))
                    .orElse(null);
            if (dataset != null) {
                resolved.add(new ResolvedPin(resource, dataset.getId(), dataset.getSha256()));
            } else if (manifest.declaresResources()) {
                missing.add(resource);
            }
        }
        if (!missing.isEmpty()) {
            throw new DataApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MISSING_BINDING",
                    "Bind a file to " + String.join(", ", missing) + " before running this agent",
                    Map.of("resources", missing));
        }
        return resolved;
    }

    /** Creates the run and its pins in one transaction, so a run never exists without what it reads. */
    @Transactional
    public Run saveWithPins(Run pending, List<ResolvedPin> resolved) {
        Run run = runs.save(pending);
        for (ResolvedPin pin : resolved) {
            pins.save(new RunDatasetPin(run.getId(), pin.resource(), pin.datasetId(), pin.sha256()));
        }
        return run;
    }
}
