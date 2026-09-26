package com.agentstore.platform.data;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** A file plugged into one resource of one owner (a connection today). Unique per (owner, resource). */
@Entity
@Table(name = "dataset_binding")
@Getter
@Setter
@NoArgsConstructor
public class DatasetBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private BindingOwnerType ownerType;

    private UUID ownerId;
    private String resourceName;
    private UUID datasetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "check_result", columnDefinition = "jsonb")
    private String checkResult = "{}";

    private UUID boundByUserId;
    private Instant boundAt;

    public DatasetBinding(BindingOwnerType ownerType, UUID ownerId, String resourceName, UUID datasetId,
                          String checkResult, UUID boundByUserId) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.resourceName = resourceName;
        this.datasetId = datasetId;
        this.checkResult = checkResult;
        this.boundByUserId = boundByUserId;
    }

    @PrePersist
    void onCreate() {
        if (boundAt == null) boundAt = Instant.now();
    }
}
