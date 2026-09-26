package com.agentstore.platform.data;

import com.agentstore.platform.execution.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunDatasetPinRepository extends JpaRepository<RunDatasetPin, RunDatasetPinId> {

    List<RunStatus> OPEN_STATUSES = Arrays.stream(RunStatus.values()).filter(s -> !s.isTerminal()).toList();

    List<RunDatasetPin> findByIdRunId(UUID runId);

    Optional<RunDatasetPin> findByIdRunIdAndIdResourceName(UUID runId, String resourceName);

    @Query("select count(p) from RunDatasetPin p, com.agentstore.platform.execution.Run r "
            + "where r.id = p.id.runId and p.datasetId = :datasetId and r.status in :statuses")
    long countPinnedByRunsIn(@Param("datasetId") UUID datasetId, @Param("statuses") Collection<RunStatus> statuses);

    /** True while a run that has not ended reads this file. */
    default boolean pinnedByOpenRun(UUID datasetId) {
        return countPinnedByRunsIn(datasetId, OPEN_STATUSES) > 0;
    }
}
