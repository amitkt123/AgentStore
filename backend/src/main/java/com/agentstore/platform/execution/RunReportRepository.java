package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RunReportRepository extends JpaRepository<RunReport, UUID> {

    @Query(value = "SELECT rr.* FROM run_report rr JOIN run r ON r.id = rr.run_id " +
            "WHERE r.agent_version_id = :versionId AND r.mode = 'SANDBOX' " +
            "ORDER BY rr.created_at DESC LIMIT 1", nativeQuery = true)
    Optional<RunReport> findLatestForVersion(@Param("versionId") UUID versionId);
}
