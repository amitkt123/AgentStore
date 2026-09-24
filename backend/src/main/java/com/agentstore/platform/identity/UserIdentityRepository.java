package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UserIdentity.Key> {
    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);

    boolean existsByProviderAndUserId(String provider, UUID userId);
}
