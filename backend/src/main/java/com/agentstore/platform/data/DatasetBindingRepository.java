package com.agentstore.platform.data;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasetBindingRepository extends JpaRepository<DatasetBinding, UUID> {

    Optional<DatasetBinding> findByOwnerTypeAndOwnerIdAndResourceName(BindingOwnerType ownerType, UUID ownerId,
                                                                      String resourceName);

    List<DatasetBinding> findByOwnerTypeAndOwnerId(BindingOwnerType ownerType, UUID ownerId);

    List<DatasetBinding> findByDatasetIdIn(Collection<UUID> datasetIds);

    boolean existsByDatasetId(UUID datasetId);

    void deleteByDatasetId(UUID datasetId);
}
