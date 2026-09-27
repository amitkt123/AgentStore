package com.agentstore.platform.data;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasetRepository extends JpaRepository<Dataset, UUID> {

    Optional<Dataset> findByIdAndOwnerOrgId(UUID id, UUID ownerOrgId);

    List<Dataset> findByOwnerOrgIdAndPurposeAndDeletedAtIsNullOrderByCreatedAtDesc(UUID ownerOrgId, DatasetPurpose purpose);

    /** Row lock for bind, delete and purge, so they cannot interleave on one file. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Dataset d where d.id = :id")
    Optional<Dataset> findByIdForUpdate(@Param("id") UUID id);

    /** Originals plus their JSONL copies of non-purged datasets (outputs are not counted). */
    @Query("select coalesce(sum(d.sizeBytes + d.recordsSizeBytes), 0) from Dataset d "
            + "where d.ownerOrgId = :orgId and d.purgedAt is null")
    long usedBytes(@Param("orgId") UUID orgId);

    @Query("select d from Dataset d where d.purgeAfter <= :now and d.purgedAt is null")
    List<Dataset> findDueForPurge(@Param("now") Instant now);

    @Query("select coalesce(sum(d.sizeBytes), 0) from Dataset d "
            + "where d.ownerOrgId = :orgId and d.purpose = :purpose and d.purgedAt is null")
    long usedBytesByPurpose(@Param("orgId") java.util.UUID orgId, @Param("purpose") DatasetPurpose purpose);
}
