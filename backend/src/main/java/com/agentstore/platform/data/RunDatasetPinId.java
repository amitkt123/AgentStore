package com.agentstore.platform.data;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class RunDatasetPinId implements Serializable {

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "resource_name")
    private String resourceName;
}
