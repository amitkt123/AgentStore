package com.agentstore.platform.publishing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewItemRepository extends JpaRepository<ReviewItem, UUID> {
    List<ReviewItem> findByStatus(ReviewStatus status);

    Optional<ReviewItem> findByAgentVersionId(UUID agentVersionId);
}
