package com.agentstore.platform.execution;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecretRepository extends JpaRepository<Secret, UUID> {
    Optional<Secret> findByConnectionIdAndName(UUID connectionId, String name);

    List<Secret> findByConnectionId(UUID connectionId);
}
