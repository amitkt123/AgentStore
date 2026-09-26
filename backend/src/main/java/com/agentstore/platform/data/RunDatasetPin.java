package com.agentstore.platform.data;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** The file a run reads for one resource, fixed when the run was created. */
@Entity
@Table(name = "run_dataset_pin")
@Getter
@NoArgsConstructor
public class RunDatasetPin {

    @EmbeddedId
    private RunDatasetPinId id;

    private UUID datasetId;
    private String sha256;

    public RunDatasetPin(UUID runId, String resourceName, UUID datasetId, String sha256) {
        this.id = new RunDatasetPinId(runId, resourceName);
        this.datasetId = datasetId;
        this.sha256 = sha256;
    }
}
