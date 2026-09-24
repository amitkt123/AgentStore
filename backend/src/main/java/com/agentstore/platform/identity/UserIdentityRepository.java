package com.agentstore.platform.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserIdentityRepository extends JpaRepository<UserIdentity, UserIdentity.Key> {
    Optional<UserIdentity> findByProviderAndSubject(String provider, String subject);
}
