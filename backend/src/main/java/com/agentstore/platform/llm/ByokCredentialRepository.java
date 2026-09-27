package com.agentstore.platform.llm;

import com.agentstore.platform.manifest.LlmVendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ByokCredentialRepository extends JpaRepository<ByokCredential, UUID> {

    Optional<ByokCredential> findByDeveloperOrgIdAndProvider(UUID developerOrgId, LlmVendor provider);

    List<ByokCredential> findByDeveloperOrgIdOrderByProviderAsc(UUID developerOrgId);

    void deleteByDeveloperOrgIdAndProvider(UUID developerOrgId, LlmVendor provider);
}
